package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.CaptureColorModel
import io.aequicor.heartbeat.feature.computeruse.api.CaptureEncoding
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.EncodedFrame
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore
import io.aequicor.heartbeat.feature.computeruse.impl.domain.PixelGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Encodes frames with the JDK codecs: lossless PNG and lossy JPEG. */
internal class DesktopFrameEncoder : FrameEncoder {
    private val log = Log.tag("DesktopFrameEncoder")

    override suspend fun encode(frame: PixelGrid, encoding: CaptureEncoding): EncodedFrame? {
        val image = frame.toImage(encoding.colorModel)
        val output = ByteArrayOutputStream()
        val formatName = when (encoding.format) {
            CaptureFormat.Png -> PNG_NAME
            CaptureFormat.Jpeg -> JPEG_NAME
        }
        val writers = ImageIO.getImageWritersByFormatName(formatName)
        if (!writers.hasNext()) {
            log.w { "no codec for format=$formatName" }
            return null
        }
        val writer = writers.next()
        val stream = ImageIO.createImageOutputStream(output)
        try {
            val parameter = writer.defaultWriteParam
            if (encoding.format == CaptureFormat.Jpeg && parameter.canWriteCompressed()) {
                parameter.compressionMode = ImageWriteParam.MODE_EXPLICIT
                parameter.compressionQuality = encoding.quality / QUALITY_DIVISOR
            }
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), parameter)
        } finally {
            stream.close()
            writer.dispose()
        }
        val content = output.toByteArray()
        log.d { "frame encoded format=$formatName size=${image.width}x${image.height} bytes=${content.size}" }
        return EncodedFrame(encoding.format, image.width, image.height, content)
    }

    override suspend fun decode(content: ByteArray): PixelGrid? {
        val image = try {
            ImageIO.read(ByteArrayInputStream(content))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "frame decode failed bytes=${content.size}" }
            null
        }
        return image?.toGrid()
    }

    private fun PixelGrid.toImage(colorModel: CaptureColorModel): BufferedImage {
        val type = when (colorModel) {
            CaptureColorModel.Rgb -> BufferedImage.TYPE_INT_RGB
            CaptureColorModel.Gray8, CaptureColorModel.Indexed -> BufferedImage.TYPE_BYTE_GRAY
        }
        val image = BufferedImage(widthPx, heightPx, type)
        for (y in 0 until heightPx) {
            for (x in 0 until widthPx) {
                image.setRGB(x, y, pixel(x, y) or OPAQUE_MASK)
            }
        }
        return image
    }

    private fun BufferedImage.toGrid(): PixelGrid {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = getRGB(x, y)
            }
        }
        return PixelGrid(width, height, pixels)
    }

    private companion object {
        const val PNG_NAME = "png"
        const val JPEG_NAME = "jpeg"
        const val QUALITY_DIVISOR = 100f
        const val OPAQUE_MASK = -0x1000000
    }
}

/**
 * Stores frames under the application storage root, one directory per capture session, and deletes the whole
 * directory when the session is purged. Paths are host data and never reach a log record.
 */
internal class DesktopFrameStore(private val root: () -> String, private val dispatchers: DispatcherProvider) :
    FrameStore {
    private val log = Log.tag("DesktopFrameStore")

    override suspend fun write(session: CaptureSessionId, id: CaptureId, frame: EncodedFrame): String =
        withContext(dispatchers.io) {
            val directory = directory(session)
            Files.createDirectories(directory)
            val file = directory.resolve(id.value + "." + frame.format.extension())
            Files.write(file, frame.content)
            log.d { "frame written id=$id session=$session bytes=${frame.content.size}" }
            file.toString()
        }

    override suspend fun read(path: String): ByteArray? = withContext(dispatchers.io) {
        val file = Path.of(path)
        if (!Files.exists(file)) {
            log.w { "stored frame is gone" }
            return@withContext null
        }
        Files.readAllBytes(file)
    }

    override suspend fun delete(session: CaptureSessionId): Unit = withContext(dispatchers.io) {
        val directory = directory(session)
        if (!Files.exists(directory)) {
            log.d { "nothing stored for session=$session" }
            return@withContext
        }
        var deleted = 0
        try {
            Files.newDirectoryStream(directory).use { files ->
                for (file in files) {
                    if (Files.deleteIfExists(file)) deleted++
                }
            }
            Files.deleteIfExists(directory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "frame purge failed session=$session deleted=$deleted" }
        }
        log.i { "frames deleted session=$session count=$deleted" }
    }

    private fun directory(session: CaptureSessionId): Path =
        Path.of(root()).resolve(SESSION_DIRECTORY).resolve(session.value)

    private companion object {
        const val SESSION_DIRECTORY = "computer-use"
    }
}

private fun CaptureFormat.extension(): String = when (this) {
    CaptureFormat.Png -> "png"
    CaptureFormat.Jpeg -> "jpg"
}
