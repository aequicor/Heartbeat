package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.SavedBundle
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.serialization.KSerializer

internal class BrowserTestDispatchers(dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main = dispatcher
    override val default = dispatcher
    override val io = dispatcher
}

internal class BrowserTestScope(override val coroutineScope: CoroutineScope) : ScopeHandle {
    private val closeActions = mutableListOf<() -> Unit>()
    override val name = "test/browser"
    override var isClosed = false
        private set
    override val savedState = object : ScopeSavedState {
        override fun <T : Any> consume(key: String, serializer: KSerializer<T>): T? = null
        override fun <T : Any> register(key: String, serializer: KSerializer<T>, supplier: () -> T?) = Unit
        override fun unregister(key: String) = Unit
        override fun snapshot(): SavedBundle = SavedBundle(emptyMap())
    }

    override fun onClose(action: () -> Unit): DisposableHandle {
        closeActions += action
        return DisposableHandle { closeActions.remove(action) }
    }

    fun close() {
        isClosed = true
        closeActions.toList().forEach { it() }
        closeActions.clear()
    }
}

internal class RecordingBrowserController : BrowserViewController {
    val commands = mutableListOf<BrowserViewCommand>()
    override fun execute(command: BrowserViewCommand) {
        commands += command
    }
}
