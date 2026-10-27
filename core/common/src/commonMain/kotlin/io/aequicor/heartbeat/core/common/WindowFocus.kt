package io.aequicor.heartbeat.core.common

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Whether somebody is attending the application: its window is focused, or an agent is driving the computer in
 * front of the user. Platforms without a window stay attended.
 */
public interface WindowFocus {
    /** Latest focus of the application window. */
    public val isFocused: StateFlow<Boolean>
}

/** Written by the entry point that owns the window; everything else only reads [WindowFocus]. */
public interface MutableWindowFocus : WindowFocus {
    /** Publishes the focus of the window. */
    public fun update(isFocused: Boolean)
}

/** Holder of the focus state. Only `WindowFocusBindings` creates it: the application graph keeps exactly one. */
public class WindowFocusState : MutableWindowFocus {
    private val log = Log.tag("WindowFocus")
    private val state = MutableStateFlow(true)
    override val isFocused: StateFlow<Boolean> get() = state

    override fun update(isFocused: Boolean) {
        if (state.getAndUpdate { isFocused } != isFocused) log.v { "Window attended: $isFocused" }
    }
}

/** Focus for tests and tools that never show a window. */
public object AlwaysFocused : WindowFocus {
    override val isFocused: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
