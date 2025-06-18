//
//  PhotoCaptureDelegate.swift
//  mrousavy
//
//  Created by Marc Rousavy on 15.12.20.
//  Copyright © 2020 mrousavy. All rights reserved.
//

import AVFoundation
import UIKit
import Accelerate
import CoreImage

// MARK: - PhotoCaptureDelegate

class PhotoCaptureDelegate: GlobalReferenceHolder, AVCapturePhotoCaptureDelegate {
  private let promise: Promise
  private let enableShutterSound: Bool
  private let cameraSessionDelegate: CameraSessionDelegate?
  private let metadataProvider: MetadataProvider
  private let path: URL

  required init(promise: Promise,
                enableShutterSound: Bool,
                metadataProvider: MetadataProvider,
                path: URL,
                cameraSessionDelegate: CameraSessionDelegate?) {
    self.promise = promise
    self.enableShutterSound = enableShutterSound
    self.metadataProvider = metadataProvider
    self.path = path
    self.cameraSessionDelegate = cameraSessionDelegate
    super.init()
    makeGlobal()
  }

    func photoOutput(_: AVCapturePhotoOutput, willCapturePhotoFor _: AVCaptureResolvedPhotoSettings) {
        if !enableShutterSound {
            // disable system shutter sound (see https://stackoverflow.com/a/55235949/5281431)
            AudioServicesDisposeSystemSoundID(1108)
        }
        
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
            let path = try FileUtils.writeUIImageToTempFile(image: image.correctImageOrientation())
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
            if let variance = depthStandardDeviation(photo: photo) {
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
    
    func depthStandardDeviation(photo: AVCapturePhoto) -> Float? {
        // 1. Grab and convert the depth map to Float32
        guard let depthData = photo.depthData?
                .converting(toDepthDataType: kCVPixelFormatType_DepthFloat32) else {
            print("No depth data")
            return nil
        }
        
        let buffer = depthData.depthDataMap
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        
        // 2. Point to the base address as Float32
        let width  = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let count  = width * height
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return 0 }
        let ptr = base.assumingMemoryBound(to: Float32.self)
        
        // 3. Compute mean
        var mean: Float = 0
        vDSP_meanv(ptr, 1, &mean, vDSP_Length(count))
        
        // 4. Compute mean-of-squares
        var meanOfSquares: Float = 0
        vDSP_measqv(ptr, 1, &meanOfSquares, vDSP_Length(count))
        
        // 5. σ = sqrt(E[x²] – μ²)
        let variance = meanOfSquares - mean * mean
        let stdDev = variance > 0 ? sqrt(variance) : 0
            
        // 6. Round to two decimal places
        let rounded = (stdDev * 100).rounded() / 100
        return rounded
    }
    
    func getOrientation(forExifOrientation exifOrientation: CGImagePropertyOrientation) -> String {
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
    
    func getIsMirrored(forExifOrientation exifOrientation: CGImagePropertyOrientation) -> Bool {
        switch exifOrientation {
        case .upMirrored, .rightMirrored, .downMirrored, .leftMirrored:
            return true
        default:
            return false
        }
    }
}

extension UIImage {
    func correctImageOrientation() -> UIImage {
        if self.imageOrientation == .up {
            return self
        }
        UIGraphicsBeginImageContextWithOptions(self.size, false, self.scale)
        self.draw(in: CGRect(origin: .zero, size: self.size))
        let normalizedImage = UIGraphicsGetImageFromCurrentImageContext()!
        UIGraphicsEndImageContext()
        return normalizedImage
    }
    
    func resizeProportionallySync(to maximumSize: CGSize) -> UIImage? {
        let aspectWidth = maximumSize.width / size.width
        let aspectHeight = maximumSize.height / size.height
        let aspectRatio = min(aspectWidth, aspectHeight)
        let newSize = CGSize(width: size.width * aspectRatio, height: size.height * aspectRatio)
        return resizeSync(size: newSize)
    }
    
    func resizeSync(size: CGSize) -> UIImage? {
        guard let cgImage = cgImage else { return nil }
        
        var format = vImage_CGImageFormat(
            bitsPerComponent: 8,
            bitsPerPixel: 32,
            colorSpace: nil,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.first.rawValue),
            version: 0,
            decode: nil,
            renderingIntent: CGColorRenderingIntent.defaultIntent
        )
        
        var sourceBuffer = vImage_Buffer()
        defer { free(sourceBuffer.data) }
        
        var error = vImageBuffer_InitWithCGImage(&sourceBuffer, &format, nil, cgImage, numericCast(kvImageNoFlags))
        guard error == kvImageNoError else { return nil }
        
        let destWidth = Int(size.width)
        let destHeight = Int(size.height)
        let bytesPerPixel = cgImage.bitsPerPixel / 8
        let destBytesPerRow = destWidth * bytesPerPixel
        let destData = UnsafeMutablePointer<UInt8>.allocate(capacity: destHeight * destBytesPerRow)
        defer { destData.deallocate() }
        
        var destBuffer = vImage_Buffer(
            data: destData,
            height: vImagePixelCount(destHeight),
            width: vImagePixelCount(destWidth),
            rowBytes: destBytesPerRow
        )
        
        // scale the image
        error = vImageScale_ARGB8888(&sourceBuffer, &destBuffer, nil, numericCast(kvImageHighQualityResampling))
        guard error == kvImageNoError else { return nil }
        
        // create a CGImage from vImage_Buffer
        var destCGImage = vImageCreateCGImageFromBuffer(
            &destBuffer,
            &format,
            nil,
            nil,
            numericCast(kvImageNoFlags),
            &error
        )?.takeRetainedValue()
        
        guard error == kvImageNoError else { return nil }
        
        // create a UIImage
        let resizedImage = destCGImage.flatMap { UIImage(cgImage: $0, scale: 0.0, orientation: self.imageOrientation) }
        destCGImage = nil
        
        return resizedImage
    }
}
