package com.mrousavy.camera.core

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.mrousavy.camera.core.extensions.id
import com.mrousavy.camera.core.extensions.takePicture
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.types.TakePhotoOptions
import com.mrousavy.camera.core.utils.FileUtils
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.pow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.hardware.camera2.*
import android.media.Image
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


fun getDepthVariance(cameraId: String, cameraManager: CameraManager): Double? {
  try {
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

    return if (capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
      0.00
    } else {
      null
    }
  } catch (e: Exception) {
    println("DepthCheckError-getDepthVariance-CameraSessionPhoto: ${e.message}")
    return null
  }
}

fun rotateImageIfNeeded(photoFile: File): File {
  val bitmap = BitmapFactory.decodeFile(photoFile.path)
  var exif: ExifInterface? = null
  try {
    exif = ExifInterface(photoFile)
  } catch (e: IOException) {
    e.printStackTrace()
  }

  val orientation = exif?.getAttributeInt(
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.ORIENTATION_NORMAL
  )

  val matrix = Matrix()
  val degrees = when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> 0f
  }

  matrix.postRotate(degrees)
  val rotatedBitmap = Bitmap.createBitmap(
    bitmap,
    0,
    0,
    bitmap.width,
    bitmap.height,
    matrix,
    true
  )

  val rotatedFile = File(photoFile.parent, "rotated_${photoFile.name}")
  FileOutputStream(rotatedFile).use { out ->
    rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
  }
  return rotatedFile
}

private suspend fun captureDepth16Image(
  cameraManager: CameraManager,
  cameraId: String,
  width: Int,
  height: Int
): android.media.Image? = suspendCancellableCoroutine { cont ->
  val depthReader = ImageReader.newInstance(width, height, ImageFormat.DEPTH16, 1)
  val handlerThread = HandlerThread("DepthCaptureThread").apply { start() }
  val handler = Handler(handlerThread.looper)

  val cameraDeviceCallback = object : CameraDevice.StateCallback() {
    override fun onOpened(cameraDevice: CameraDevice) {
      val targets = listOf(depthReader.surface)
      cameraDevice.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
          val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
          requestBuilder.addTarget(depthReader.surface)
          session.capture(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {}, handler)
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
          cont.resume(null)
        }
      }, handler)
    }
    override fun onDisconnected(cameraDevice: CameraDevice) { cont.resume(null) }
    override fun onError(cameraDevice: CameraDevice, error: Int) { cont.resume(null) }
  }

  depthReader.setOnImageAvailableListener({ reader ->
    val image = reader.acquireLatestImage()
    cont.resume(image)
    reader.close()
    handlerThread.quitSafely()
  }, handler)

  cameraManager.openCamera(cameraId, cameraDeviceCallback, handler)
}

suspend fun CameraSession.takePhoto(options: TakePhotoOptions): Photo {
  val cameraId = camera2.cameraIdList.firstOrNull() ?: throw CameraNotReadyError()
  val characteristics = camera2.getCameraCharacteristics(cameraId)
  val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
  val outputFormats = map?.outputFormats ?: intArrayOf()
  val supportsDepth16 = outputFormats.contains(ImageFormat.DEPTH16)

  val width = options.file.file?.let { FileUtils.getImageSize(it.path).width } ?: 1280
  val height = options.file.file?.let { FileUtils.getImageSize(it.path).height } ?: 720

  val jpegReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
  val depthReader = if (supportsDepth16) ImageReader.newInstance(width, height, ImageFormat.DEPTH16, 1) else null
  val handlerThread = HandlerThread("Camera2CaptureThread").apply { start() }
  val handler = Handler(handlerThread.looper)

  val surfaces = mutableListOf(jpegReader.surface)
  if (depthReader != null) surfaces.add(depthReader.surface)

  val photoResult = suspendCancellableCoroutine<Triple<File, Int, Int>> { cont ->
    jpegReader.setOnImageAvailableListener({ reader ->
      val image = reader.acquireLatestImage()
      val buffer = image.planes[0].buffer
      val bytes = ByteArray(buffer.remaining())
      buffer.get(bytes)
      val photoFile = options.file.file ?: File.createTempFile("photo", ".jpg")
      photoFile.writeBytes(bytes)
      val size = FileUtils.getImageSize(photoFile.path)
      image.close()
      cont.resume(Triple(photoFile, size.width, size.height))
    }, handler)
  }

  var depthVariance: Double? = null
  if (depthReader != null) {
    val depthImage = suspendCancellableCoroutine<Image?> { cont ->
      depthReader.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage()
        cont.resume(image)
        reader.close()
        handlerThread.quitSafely()
      }, handler)
    }
    depthImage?.let {
      val buffer = it.planes[0].buffer
      val shortBuffer = buffer.asShortBuffer()
      val depthValues = ShortArray(shortBuffer.remaining())
      shortBuffer.get(depthValues)
      val validDepths = depthValues.filter { v -> v > 0 }
      depthVariance = if (validDepths.isNotEmpty()) {
        val mean = validDepths.average()
        validDepths.map { v -> (v - mean).pow(2) }.average()
      } else 0.0
      it.close()
    }
    depthReader.close()
  }

  // Verifica el permiso de cámara antes de abrirla
  val context = this.context
  if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
    throw SecurityException("Camera permission not granted")
  }

  val cameraDevice = suspendCancellableCoroutine<CameraDevice> { cont ->
    try {
      camera2.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) { cont.resume(device) }
        override fun onDisconnected(device: CameraDevice) { cont.resumeWith(Result.failure(CameraNotReadyError())) }
        override fun onError(device: CameraDevice, error: Int) { cont.resumeWith(Result.failure(CameraNotReadyError())) }
      }, handler)
    } catch (e: SecurityException) {
      cont.resumeWith(Result.failure(e))
    }
  }

  suspendCancellableCoroutine<Unit> { cont ->
    cameraDevice.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
      override fun onConfigured(session: CameraCaptureSession) {
        val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        surfaces.forEach { requestBuilder.addTarget(it) }
        session.capture(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
          override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            cont.resume(Unit)
          }
        }, handler)
      }
      override fun onConfigureFailed(session: CameraCaptureSession) { cont.resume(Unit) }
    }, handler)
  }

  handlerThread.quitSafely()

  val (photoFile, widthResult, heightResult) = photoResult
  val rotatedPhotoFile = rotateImageIfNeeded(photoFile)

  // Determina si la imagen debe ser espejada (mirrored)
  val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
  val isFrontCamera = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
  // Si tienes una opción en options/config para forzar mirror, úsala aquí:
  val isMirrored = isFrontCamera // o tu lógica personalizada

  return Photo(rotatedPhotoFile.path, widthResult, heightResult, Orientation.PORTRAIT, isMirrored, depthVariance)
}

private val AudioManager.isSilent: Boolean
  get() = ringerMode != AudioManager.RINGER_MODE_NORMAL
