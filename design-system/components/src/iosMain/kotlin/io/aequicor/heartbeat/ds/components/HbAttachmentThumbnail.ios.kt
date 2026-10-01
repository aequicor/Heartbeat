@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.ds.components

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberLongType
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

internal actual fun encodeAttachmentThumbnail(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty()) { "Image thumbnail source is empty" }
    val data = requireNotNull(
        bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) },
    )
    try {
        val source = requireNotNull(CGImageSourceCreateWithData(data, null)) { "Unsupported image thumbnail format" }
        try {
            requireThumbnailDimensions(source)
            return encodeIosThumbnail(source)
        } finally {
            CFRelease(source)
        }
    } finally {
        CFRelease(data)
    }
}

private fun requireThumbnailDimensions(source: CGImageSourceRef) = memScoped {
    val properties = requireNotNull(CGImageSourceCopyPropertiesAtIndex(source, 0u, null))
    try {
        val width = alloc<LongVar>()
        val height = alloc<LongVar>()
        val widthValue = requireNotNull(CFDictionaryGetValue(properties, kCGImagePropertyPixelWidth))
        val heightValue = requireNotNull(CFDictionaryGetValue(properties, kCGImagePropertyPixelHeight))
        require(CFNumberGetValue(widthValue.reinterpret(), kCFNumberLongType, width.ptr))
        require(CFNumberGetValue(heightValue.reinterpret(), kCFNumberLongType, height.ptr))
        require(
            width.value > 0 && height.value > 0 && width.value <= ATTACHMENT_THUMBNAIL_MAX_PIXELS / height.value,
        ) {
            "Attachment thumbnail source exceeds pixel limit"
        }
    } finally {
        CFRelease(properties)
    }
}

private fun encodeIosThumbnail(source: CGImageSourceRef): ByteArray = memScoped {
    val size = alloc<LongVar>().apply { value = ATTACHMENT_THUMBNAIL_EDGE.toLong() }
    val number = requireNotNull(CFNumberCreate(null, kCFNumberLongType, size.ptr))
    val keys = allocArray<COpaquePointerVar>(3)
    val values = allocArray<COpaquePointerVar>(3)
    keys[0] = kCGImageSourceCreateThumbnailFromImageAlways
    keys[1] = kCGImageSourceCreateThumbnailWithTransform
    keys[2] = kCGImageSourceThumbnailMaxPixelSize
    values[0] = kCFBooleanTrue
    values[1] = kCFBooleanTrue
    values[2] = number
    val options = requireNotNull(CFDictionaryCreate(null, keys, values, 3, null, null))
    try {
        val image = requireNotNull(CGImageSourceCreateThumbnailAtIndex(source, 0u, options))
        try {
            val png = requireNotNull(UIImagePNGRepresentation(UIImage.imageWithCGImage(image)))
            require(png.length <= ATTACHMENT_THUMBNAIL_MAX_BYTES.toULong()) { "Thumbnail exceeds byte limit" }
            ByteArray(png.length.toInt()).also { result ->
                result.usePinned { memcpy(it.addressOf(0), png.bytes, png.length) }
            }
        } finally {
            CGImageRelease(image)
        }
    } finally {
        CFRelease(options)
        CFRelease(number)
    }
}
