package com.mrousavy.camera.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.mrousavy.camera.core.extensions.id
import com.mrousavy.camera.core.extensions.takePicture
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.types.TakePhotoOptions
import com.mrousavy.camera.core.utils.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


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

suspend fun CameraSession.takePhoto(options: TakePhotoOptions): Photo {
  val camera = camera ?: throw CameraNotReadyError()
  val cameraId = camera.cameraInfo.id ?: "0"
  val configuration = configuration ?: throw CameraNotReadyError()
  val photoConfig = configuration.photo as? CameraConfiguration.Output.Enabled<CameraConfiguration.Photo> ?: throw PhotoNotEnabledError()
  val photoOutput = photoOutput ?: throw PhotoNotEnabledError()

  // Flash
  if (options.flash != Flash.OFF && !camera.cameraInfo.hasFlashUnit()) {
    throw FlashUnavailableError()
  }
  photoOutput.flashMode = options.flash.toFlashMode()
  // Shutter sound
  val enableShutterSound = options.enableShutterSound && !audioManager.isSilent
  // isMirrored (EXIF)
  val isMirrored = photoConfig.config.isMirrored

  // Shoot photo!
  val photoFile = photoOutput.takePicture(
    options.file.file,
    isMirrored,
    enableShutterSound,
    metadataProvider,
    callback,
    CameraQueues.cameraExecutor
  )

  // Parse resulting photo (EXIF data)
  val size = FileUtils.getImageSize(photoFile.uri.path)

  val rotatedPhotoFile = rotateImageIfNeeded(File(photoFile.uri.path))

  val depthVariance = getDepthVariance(cameraId,camera2)

  return Photo(rotatedPhotoFile.path, size.width, size.height, Orientation.PORTRAIT, isMirrored, depthVariance)
}

private val AudioManager.isSilent: Boolean
  get() = ringerMode != AudioManager.RINGER_MODE_NORMAL
