package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.compositionLocalOf

/** Opaque identity of one modal lease; native siblings are hidden while another lease is foremost. */
public class HbNativeModalOwner internal constructor() {
    internal companion object {
        const val PROPERTY = "heartbeat.native.modalOwner"
    }
}

/** The native content owner of the current dialog, or null for ordinary application content. */
public val LocalHbNativeModalOwner = compositionLocalOf<HbNativeModalOwner?> { null }

/** The foremost modal in this window; native interop and its overlaid controls follow the same visibility. */
public val LocalHbActiveNativeModalOwner = compositionLocalOf<HbNativeModalOwner?> { null }
