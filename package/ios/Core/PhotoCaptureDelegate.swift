//
//  PhotoCaptureDelegate.swift
//  mrousavy
//
//  Created by Marc Rousavy on 15.12.20.
//  Copyright © 2020 mrousavy. All rights reserved.
//

import AVFoundation
import UIKit

// MARK: - PhotoCaptureDelegate

class PhotoCaptureDelegate: GlobalReferenceHolder, AVCapturePhotoCaptureDelegate {
  private let promise: Promise
  private let enableShutterSound: Bool
  private let cameraSessionDelegate: CameraSessionDelegate?
  private let metadataProvider: MetadataProvider
  private let currentOrientation: UIInterfaceOrientation

  required init(promise: Promise,
                enableShutterSound: Bool,
                metadataProvider: MetadataProvider,
                cameraSessionDelegate: CameraSessionDelegate?,
                currentOrientation: UIInterfaceOrientation) {
    self.promise = promise
    self.enableShutterSound = enableShutterSound
    self.metadataProvider = metadataProvider
    self.cameraSessionDelegate = cameraSessionDelegate
    self.currentOrientation = currentOrientation
    super.init()
    makeGlobal()
  }

  func photoOutput(_: AVCapturePhotoOutput, willCapturePhotoFor _: AVCaptureResolvedPhotoSettings) {
    if !enableShutterSound {
      // disable system shutter sound (see https://stackoverflow.com/a/55235949/5281431)
      AudioServicesDisposeSystemSoundID(1108)
    }

    // onShutter(..) event
    cameraSessionDelegate?.onCaptureShutter(shutterType: .photo)
  }

  func photoOutput(_: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
    defer {
      removeGlobal()
    }
    if let error = error as NSError? {
      promise.reject(error: .capture(.unknown(message: error.description)), cause: error)
      return
    }

    do {
      guard let imageData = photo.fileDataRepresentation(), let image = UIImage(data: imageData) else {
          promise.reject(error: .capture(.imageDataAccessError))
        return
      }
      let transformedimage = image.transformedImage(interfaceOrientation: currentOrientation)
      let path = try FileUtils.writeUIImageToTempFile(image: transformedimage)

      let exif = photo.metadata["{Exif}"] as? [String: Any]
      let width = exif?["PixelXDimension"]
      let height = exif?["PixelYDimension"]
      let exifOrientation = photo.metadata[String(kCGImagePropertyOrientation)] as? UInt32 ?? CGImagePropertyOrientation.up.rawValue
      let cgOrientation = CGImagePropertyOrientation(rawValue: exifOrientation) ?? CGImagePropertyOrientation.up
      let orientation = getOrientation(forExifOrientation: cgOrientation)
      let isMirrored = getIsMirrored(forExifOrientation: cgOrientation)
    
      var response = [
        "path": path.absoluteString,
        "width": width as Any,
        "height": height as Any,
        "orientation": orientation,
        "isMirrored": isMirrored,
        "isRawPhoto": photo.isRawPhoto,
        "metadata": photo.metadata,
        "thumbnail": photo.embeddedThumbnailPhotoFormat as Any,
      ]
      if let variance = calculateDepthVariabilityWithWeights(photo: photo) {
        response["depth_variance"] = variance
      }
      promise.resolve(response)
    } catch let error as CameraError {
      promise.reject(error: error)
    } catch {
      promise.reject(error: .capture(.unknown(message: "An unknown error occured while capturing the photo!")), cause: error as NSError)
    }
  }

  func photoOutput(_: AVCapturePhotoOutput, didFinishCaptureFor _: AVCaptureResolvedPhotoSettings, error: Error?) {
    defer {
      removeGlobal()
    }
    if let error = error as NSError? {
      if error.code == -11807 {
        promise.reject(error: .capture(.insufficientStorage), cause: error)
      } else {
        promise.reject(error: .capture(.unknown(message: error.description)), cause: error)
      }
      return
    }
  }

    func calculateDepthVariabilityWithWeights(photo: AVCapturePhoto) -> Float? {
        guard let depthData = photo.depthData else {
            print("No depth data available.")
            return nil
        }

        let convertedDepthData = depthData.converting(toDepthDataType: kCVPixelFormatType_DepthFloat32)
        let depthMap = CIImage(cvPixelBuffer: convertedDepthData.depthDataMap)
        let context = CIContext()
        let depthMapSize = depthMap.extent.size

        // Sample points with weights
        let weightedPoints: [(point: CGPoint, weight: Float)] = [
            (CGPoint(x: depthMapSize.width * 0.5, y: depthMapSize.height * 0.5), 3.0), // center with higher weight
            (CGPoint(x: depthMapSize.width * 0.25, y: depthMapSize.height * 0.25), 1.0), // corners with normal weight
            (CGPoint(x: depthMapSize.width * 0.75, y: depthMapSize.height * 0.25), 1.0),
            (CGPoint(x: depthMapSize.width * 0.25, y: depthMapSize.height * 0.75), 1.0),
            (CGPoint(x: depthMapSize.width * 0.75, y: depthMapSize.height * 0.75), 1.0)
        ]

        var totalWeight: Float = 0
        var weightedSum: Float = 0
        var depths: [Float] = []
        for (point, weight) in weightedPoints {
            var pixelDepth: Float = 0
            let pointX = Int(point.x)
            let pointY = Int(point.y)
            context.render(depthMap, toBitmap: &pixelDepth, rowBytes: 4, bounds: CGRect(x: pointX, y: pointY, width: 1, height: 1), format: .Rf, colorSpace: nil)
            depths.append(pixelDepth)
            weightedSum += pixelDepth * weight
            totalWeight += weight
        }

        let weightedMean = weightedSum / totalWeight
        let weightedVariance = weightedPoints.indices.map {
            weightedPoints[$0].weight * (depths[$0] - weightedMean) * (depths[$0] - weightedMean)
        }.reduce(0, +) / totalWeight

        return sqrt(weightedVariance) // Standard deviation
    }

    private func getOrientation(forExifOrientation exifOrientation: CGImagePropertyOrientation) -> String {
        switch exifOrientation {
        case .up, .upMirrored:
            return "portrait"
        case .down, .downMirrored:
            return "portrait-upside-down"
        case .left, .leftMirrored:
            return "landscape-left"
        case .right, .rightMirrored:
            return "landscape-right"
        default:
            return "portrait"
        }
    }

    private func getIsMirrored(forExifOrientation exifOrientation: CGImagePropertyOrientation) -> Bool {
        switch exifOrientation {
        case .upMirrored, .rightMirrored, .downMirrored, .leftMirrored:
            return true
        default:
            return false
        }
    }
}

extension UIImage {
    func transformedImage(interfaceOrientation: UIInterfaceOrientation) -> UIImage {
        switch interfaceOrientation {
        case .landscapeRight:
            return rotate(degrees: 90)

        case .landscapeLeft:
            return rotate(degrees: -90)

        case .portraitUpsideDown:
            return rotate(degrees: 180)

        case .portrait:
            return self

        default:
            return self
        }
    }

    func rotate(degrees: CGFloat) -> UIImage {
        let radians = degrees * (CGFloat(Double.pi) / 180)
        let rotatedSize = CGRect(origin: .zero, size: size)
            .applying(CGAffineTransform(rotationAngle: CGFloat(radians)))
            .integral.size
        UIGraphicsBeginImageContext(rotatedSize)
        if let context = UIGraphicsGetCurrentContext() {
            let origin = CGPoint(x: rotatedSize.width / 2.0,
                                 y: rotatedSize.height / 2.0)
            context.translateBy(x: origin.x, y: origin.y)
            context.rotate(by: radians)
            draw(in: CGRect(x: -origin.y, y: -origin.x,
                            width: size.width, height: size.height))
            let rotatedImage = UIGraphicsGetImageFromCurrentImageContext()
            UIGraphicsEndImageContext()

            return rotatedImage ?? self
        }

        return self
    }
}
