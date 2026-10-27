package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.MutableWindowFocus
import io.aequicor.heartbeat.core.common.WindowFocus
import io.aequicor.heartbeat.core.common.WindowFocusState

/**
 * The one window-focus holder of the application graph. Desktop bundles read it through `FoundationAppBindings`
 * and never compile a copy; the desktop window writes it.
 */
@ContributesTo(AppScope::class)
@BindingContainer
public object WindowFocusBindings {
    /** Single state holder behind both the read and the write view. */
    @Provides
    @SingleIn(AppScope::class)
    internal fun state(): WindowFocusState = WindowFocusState()

    /** Read view for features. */
    @Provides
    internal fun focus(state: WindowFocusState): WindowFocus = state

    /** Write view for the platform entry point. */
    @Provides
    internal fun writable(state: WindowFocusState): MutableWindowFocus = state
}
