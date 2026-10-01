package io.aequicor.heartbeat.feature.computeruse.impl.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import org.jetbrains.skia.Image

/** Desktop image codec used by the common frame decoder. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class PlatformComputerUseFrameCodec : ComputerUseFrameCodec {
    override fun decode(content: ByteArray): ImageBitmap = Image.makeFromEncoded(content).toComposeImageBitmap()
}
