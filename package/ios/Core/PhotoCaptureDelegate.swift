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
    
    required init(promise: Promise,
                  enableShutterSound: Bool,
                  metadataProvider: MetadataProvider,
                  cameraSessionDelegate: CameraSessionDelegate?) {
        self.promise = promise
        self.enableShutterSound = enableShutterSound
        self.metadataProvider = metadataProvider
        self.cameraSessionDelegate = cameraSessionDelegate
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
//            guard let resizedImage = image.correctImageOrientation().resizeProportionallySync(to: CGSize(width: 2048, height: 2048)) else {
//                promise.reject(error: .capture(.imageDataAccessError))
//                return
//            }
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
