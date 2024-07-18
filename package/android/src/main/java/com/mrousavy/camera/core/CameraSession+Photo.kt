package com.mrousavy.camera.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.mrousavy.camera.core.extensions.takePicture
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.utils.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


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

suspend fun CameraSession.takePhoto(flash: Flash, enableShutterSound: Boolean): Photo {
  val camera = camera ?: throw CameraNotReadyError()
  val photoOutput = photoOutput ?: throw PhotoNotEnabledError()

  if (flash != Flash.OFF && !camera.cameraInfo.hasFlashUnit()) {
    throw FlashUnavailableError()
  }

  photoOutput.flashMode = flash.toFlashMode()
  val enableShutterSoundActual = getEnableShutterSoundActual(enableShutterSound)

  val photoFile = photoOutput.takePicture(context, enableShutterSoundActual, metadataProvider, callback, CameraQueues.cameraExecutor)
  val isMirrored = photoFile.metadata.isReversedHorizontal

  val size = FileUtils.getImageSize(photoFile.uri.path)

  val rotatedPhotoFile = rotateImageIfNeeded(File(photoFile.uri.path))

  return Photo(rotatedPhotoFile.path, size.width, size.height, Orientation.PORTRAIT, isMirrored)
}

private fun CameraSession.getEnableShutterSoundActual(enable: Boolean): Boolean {
  if (enable && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
    Log.i(CameraSession.TAG, "Ringer mode is silent (${audioManager.ringerMode}), disabling shutter sound...")
    return false
  }

  return enable
}
