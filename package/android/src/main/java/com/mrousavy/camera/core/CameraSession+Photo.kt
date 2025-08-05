package com.mrousavy.camera.core

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
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
  if (bitmap == null) {
    throw IOException("Failed to decode image: file is empty or corrupt (${photoFile.path})")
  }
  var exif: ExifInterface? = null
  try {
    exif = ExifInterface(photoFile)
  } catch (e: IOException) {
    throw IOException("Failed to read EXIF metadata from image (${photoFile.path})", e)
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
    if (!rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)) {
      throw IOException("Failed to save rotated image to disk (${rotatedFile.path})")
    }
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
  // --- New robust Camera2 flow ---
  Log.d("CameraSession", "START takePhoto() - options: $options")
  // Select the first back camera that supports DEPTH16, if available
  val cameraId = camera2.cameraIdList.firstOrNull { id ->
    val characteristics = camera2.getCameraCharacteristics(id)
    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
    val isBack = lensFacing == CameraCharacteristics.LENS_FACING_BACK
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val outputFormats = map?.outputFormats ?: intArrayOf()
    isBack && outputFormats.contains(ImageFormat.DEPTH16)
  } ?: camera2.cameraIdList.firstOrNull { id ->
    val characteristics = camera2.getCameraCharacteristics(id)
    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
    lensFacing == CameraCharacteristics.LENS_FACING_BACK
  } ?: camera2.cameraIdList.firstOrNull() ?: throw CameraNotReadyError()

  val characteristics = camera2.getCameraCharacteristics(cameraId)
  val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
  val outputFormats = map?.outputFormats ?: intArrayOf()
  val supportsDepth16 = outputFormats.contains(ImageFormat.DEPTH16)
  // Try lower resolution if depth capture fails
  var width = options.file.file?.let { FileUtils.getImageSize(it.path).width } ?: 1280
  var height = options.file.file?.let { FileUtils.getImageSize(it.path).height } ?: 720
  Log.d("CameraSession", "Photo dimensions before validation: width=$width, height=$height")
  if (width <= 0 || height <= 0) {
    Log.e("CameraSession", "Invalid image dimensions detected (width=$width, height=$height). Using default 1280x720.")
    width = 1280
    height = 720
  }
  // Get maximum supported JPEG resolution for the selected camera
  val maxJpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
  if (maxJpegSize != null) {
    width = maxJpegSize.width
    height = maxJpegSize.height
    Log.d("CameraSession", "Using maximum supported JPEG resolution: width=$width, height=$height")
  } else {
    Log.d("CameraSession", "No supported JPEG sizes found, using default width=$width, height=$height")
  }
  // Get maximum supported DEPTH16 resolution if available
  var depthWidth = width
  var depthHeight = height
  if (supportsDepth16) {
    val maxDepthSize = map?.getOutputSizes(ImageFormat.DEPTH16)?.maxByOrNull { it.width * it.height }
    if (maxDepthSize != null) {
      depthWidth = maxDepthSize.width
      depthHeight = maxDepthSize.height
      Log.d("CameraSession", "Using maximum supported DEPTH16 resolution: width=$depthWidth, height=$depthHeight")
    } else {
      Log.d("CameraSession", "No supported DEPTH16 sizes found, using JPEG resolution for depth: width=$depthWidth, height=$depthHeight")
    }
  }
  // Try fallback to lower resolution for depth
  val fallbackResolutions = listOf(Pair(640, 480), Pair(320, 240))
  var triedFallback = false
  var photoFile = options.file.file ?: File.createTempFile("photo", ".jpg")
  var depthVariance: Double? = null
  var photoSaved = false
  var widthResult = width
  var heightResult = height

  fun tryCapturePhoto(w: Int, h: Int): Boolean {
    val handlerThread = HandlerThread("Camera2CaptureThread").apply { start() }
    val handler = Handler(handlerThread.looper)
    val jpegReader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 1)
    val surfaces = mutableListOf(jpegReader.surface)
    val depthReader = if (supportsDepth16) ImageReader.newInstance(depthWidth, depthHeight, ImageFormat.DEPTH16, 1) else null
    if (depthReader != null) surfaces.add(depthReader.surface)
    photoSaved = false
    widthResult = w
    heightResult = h
    jpegReader.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { reader ->
      val image = reader.acquireLatestImage()
      if (image != null) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        photoFile.writeBytes(bytes)
        val size = FileUtils.getImageSize(photoFile.path)
        widthResult = size.width
        heightResult = size.height
        photoSaved = true
        image.close()
      }
    }, handler)
    if (depthReader != null) {
      depthReader.setOnImageAvailableListener(ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage()
        if (image != null) {
          val buffer = image.planes[0].buffer
          val shortBuffer = buffer.asShortBuffer()
          val depthValues = ShortArray(shortBuffer.remaining())
          shortBuffer.get(depthValues)
          val validDepths = depthValues.filter { v -> v > 0 }
          depthVariance = if (validDepths.isNotEmpty()) {
            val mean = validDepths.average()
            validDepths.map { v -> (v - mean).pow(2) }.average()
          } else 0.0
          image.close()
        }
      }, handler)
    }
    val cameraDevice = kotlinx.coroutines.runBlocking {
      suspendCancellableCoroutine<CameraDevice> { cont ->
        camera2.openCamera(cameraId, object : CameraDevice.StateCallback() {
          override fun onOpened(device: CameraDevice) { cont.resume(device) }
          override fun onDisconnected(device: CameraDevice) { cont.resumeWith(Result.failure(CameraNotReadyError())) }
          override fun onError(device: CameraDevice, error: Int) { cont.resumeWith(Result.failure(CameraNotReadyError())) }
        }, handler)
      }
    }
    // Use runBlocking to call suspend function inside non-suspend context
    kotlinx.coroutines.runBlocking {
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
    }
    handlerThread.quitSafely()
    val startWait = System.currentTimeMillis()
    while (!photoSaved && System.currentTimeMillis() - startWait < 5000) {
      Thread.sleep(50)
    }
    return photoSaved
  }

  // Try main resolution
  var success = tryCapturePhoto(width, height)
  // If failed, try fallback resolutions
  if (!success) {
    for ((w, h) in fallbackResolutions) {
      Log.d("CameraSession", "Trying fallback resolution: $w x $h")
      success = tryCapturePhoto(w, h)
      if (success) {
        triedFallback = true
        break
      }
    }
  }
  if (!success) {
    throw IOException("Failed to capture photo at any supported resolution.")
  }

  val rotatedPhotoFile = rotateImageIfNeeded(photoFile)
  val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
  val isFrontCamera = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
  val isMirrored = isFrontCamera
  return Photo(rotatedPhotoFile.path, widthResult, heightResult, Orientation.PORTRAIT, isMirrored, depthVariance)
}

private val AudioManager.isSilent: Boolean
  get() = ringerMode != AudioManager.RINGER_MODE_NORMAL
