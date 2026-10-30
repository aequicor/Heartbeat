package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.aequicor.heartbeat.core.logging.Log

/**
 * Presentation barrier for a desktop startup cover. All composed loading surfaces must leave before [isReady].
 * Overlapping navigation crossfades retain their own leases, so a new ready screen cannot reveal an outgoing loader.
 * This state owns no application work and never delays loading; the host uses it only for the initial handoff.
 */
@Stable
public class HbWindowStartupState {
    private val log = Log.tag("HbWindowStartup")
    private val loading = mutableStateMapOf<Any, Unit>()
    private var hasContent by mutableStateOf(false)

    /** True only after content composition committed and every composed loader released its lease. */
    public val isReady: Boolean get() = hasContent && loading.isEmpty()

    internal fun contentCommitted() {
        if (!hasContent) {
            hasContent = true
            log.v { "Initial window content committed" }
        }
    }

    internal fun beginLoading(token: Any) {
        loading[token] = Unit
    }

    internal fun endLoading(token: Any) {
        loading.remove(token)
    }
}

private val LocalHbWindowStartup = staticCompositionLocalOf<HbWindowStartupState?> { null }

/** Connects loading surfaces to a host's startup cover without platform/native dependencies in the feature. */
@Composable
public fun HbWindowStartupProvider(state: HbWindowStartupState, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHbWindowStartup provides state) {
        content()
        SideEffect { state.contentCommitted() }
    }
}

/** Holds the startup cover while this loading surface is composed; a no-op outside a startup provider. */
@Composable
public fun HbWindowStartupLoading() {
    val state = LocalHbWindowStartup.current ?: return
    val token = remember { Any() }
    DisposableEffect(state, token) {
        state.beginLoading(token)
        onDispose { state.endLoading(token) }
    }
}
