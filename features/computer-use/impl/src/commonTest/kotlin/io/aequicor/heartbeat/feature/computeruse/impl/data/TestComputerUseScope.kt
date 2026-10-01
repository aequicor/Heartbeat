package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

/** Scope-close recorder without creating a real dependency graph. */
internal class TestComputerUseScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
    private val callbacks = mutableListOf<() -> Unit>()
    override val name: String = "test/computer-use"
    override val savedState: ScopeSavedState get() = error("Saved state is not used by these tests")
    override var isClosed: Boolean = false
        private set

    override fun onClose(action: () -> Unit): DisposableHandle {
        if (isClosed) action() else callbacks += action
        return DisposableHandle { callbacks.remove(action) }
    }

    /** Runs close callbacks after the profile jobs would have been cancelled by the real owner. */
    fun close() {
        isClosed = true
        callbacks.asReversed().forEach { it() }
        callbacks.clear()
    }
}
