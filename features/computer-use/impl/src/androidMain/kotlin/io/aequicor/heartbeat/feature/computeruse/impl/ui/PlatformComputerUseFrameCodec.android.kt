package io.aequicor.heartbeat.feature.computeruse.impl.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope

/** Android image codec used by the common frame decoder. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class PlatformComputerUseFrameCodec : ComputerUseFrameCodec {
    override fun decode(content: ByteArray): ImageBitmap {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(content, 0, content.size)) {
            "Stored frame cannot be decoded"
        }
        // ImageBitmap retains this Bitmap; it must remain alive until Compose releases the preview.
        return bitmap.asImageBitmap()
    }
}
