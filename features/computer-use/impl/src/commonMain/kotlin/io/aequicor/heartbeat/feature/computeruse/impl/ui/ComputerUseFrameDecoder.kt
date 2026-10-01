package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Main-safe frame decoding; its caller cancels an outdated preview when the frame or session changes. */
@Inject
internal class ComputerUseFrameDecoder(private val dispatchers: DispatcherProvider) {
    private val log = Log.tag("ComputerUseFrameDecoder")

    suspend fun decode(content: ByteArray): ImageBitmap? = withContext(dispatchers.default) {
        try {
            decodeComputerUseFrame(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "frame preview decode failed bytes=${content.size}" }
            null
        }
    }
}

/** Platform image codec primitive; the shared decoder schedules it on the injected CPU dispatcher. */
internal expect fun decodeComputerUseFrame(content: ByteArray): ImageBitmap
