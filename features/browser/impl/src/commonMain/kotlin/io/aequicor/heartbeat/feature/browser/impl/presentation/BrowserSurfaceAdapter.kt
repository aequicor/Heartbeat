package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserError
import io.aequicor.heartbeat.feature.browser.api.BrowserPage
import io.aequicor.heartbeat.feature.browser.api.isBrowserUrlAllowed
import io.aequicor.heartbeat.feature.browser.impl.di.scope.BrowserScope
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Retained bridge with all mutations confined to the injected main dispatcher. Native callbacks may arrive
 * from any thread. Identity is checked after dispatch, so queued events from disposed views cannot win.
 * Commands arriving before attachment are replayed in order; a new surface restores the last safe URL.
 */
@SingleIn(BrowserScope::class)
@ContributesBinding(BrowserScope::class, binding = binding<BrowserSession>())
@ContributesBinding(BrowserScope::class, binding = binding<BrowserSurface>())
@Inject
internal class BrowserSurfaceAdapter(
    @ForScope(BrowserScope::class) private val scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    override val nativeResources: BrowserNativeResources = BrowserNativeResources.None,
) : BrowserSession,
    BrowserSurface {
    private val log = Log.tag("BrowserSurface")
    private val page = MutableStateFlow(BrowserPage())
    private var controller: BrowserViewController? = null
    private val pending = mutableListOf<BrowserViewCommand>()
    private var isEnabled = false
    private var isClosed = false

    override val pages: StateFlow<BrowserPage> = page
    override val initialUrl: String get() = page.value.url

    init {
        scope.onClose {
            isClosed = true
            isEnabled = false
            controller?.execute(BrowserViewCommand.Stop)
            controller = null
            pending.clear()
        }
    }

    override suspend fun setEnabled(isEnabled: Boolean): Unit = withContext(dispatchers.main) {
        if (isClosed) return@withContext
        log.v { "Browser availability changed: enabled=$isEnabled" }
        this@BrowserSurfaceAdapter.isEnabled = isEnabled
        if (!isEnabled) {
            val previous = controller
            controller = null
            pending.clear()
            previous?.execute(BrowserViewCommand.Stop)
            page.value = page.value.copy(
                isLoading = false,
                isBackAvailable = false,
                isForwardAvailable = false,
                error = null,
            )
        }
    }

    override suspend fun execute(command: BrowserCommand): Unit = withContext(dispatchers.main) {
        if (!isEnabled || isClosed) return@withContext
        log.v { "Executing browser navigation command" }
        val native = command.toNative()
        if (command is BrowserCommand.Load) {
            require(isBrowserUrlAllowed(command.url)) { "Browser requires an HTTP(S) address" }
            page.value = page.value.copy(url = command.url, isLoading = true, error = null)
        }
        if (command == BrowserCommand.Stop) page.value = page.value.copy(isLoading = false)
        val current = controller
        if (current == null) pending += native else current.execute(native)
    }

    override fun attached(controller: BrowserViewController) {
        scope.coroutineScope.launch(dispatchers.main) {
            if (!isEnabled || isClosed) {
                controller.execute(BrowserViewCommand.Stop)
                return@launch
            }
            if (this@BrowserSurfaceAdapter.controller === controller) return@launch
            this@BrowserSurfaceAdapter.controller?.execute(BrowserViewCommand.Stop)
            this@BrowserSurfaceAdapter.controller = controller
            val commands = pending.toList()
            pending.clear()
            commands.forEach(controller::execute)
        }
    }

    @HighFrequency
    override fun changed(controller: BrowserViewController, state: BrowserViewState) {
        scope.coroutineScope.launch(dispatchers.main) {
            if (!isEnabled || isClosed || this@BrowserSurfaceAdapter.controller !== controller) return@launch
            log.v { "Updating browser page projection" }
            if (state.url.isNotEmpty() && !isBrowserUrlAllowed(state.url)) {
                page.value = page.value.copy(isLoading = false, error = BrowserError.UnsupportedAddress)
            } else if (state.url.isNotEmpty() || page.value.url.isEmpty() || state.error != null) {
                page.value = state.toDomain(page.value.url)
            }
        }
    }

    override fun detached(controller: BrowserViewController) {
        scope.coroutineScope.launch(dispatchers.main) {
            if (this@BrowserSurfaceAdapter.controller !== controller) return@launch
            log.v { "Browser surface detached" }
            this@BrowserSurfaceAdapter.controller = null
            page.value = page.value.copy(isLoading = false, isBackAvailable = false, isForwardAvailable = false)
        }
    }
}

private fun BrowserCommand.toNative(): BrowserViewCommand = when (this) {
    is BrowserCommand.Load -> BrowserViewCommand.Load(url)
    BrowserCommand.Back -> BrowserViewCommand.Back
    BrowserCommand.Forward -> BrowserViewCommand.Forward
    BrowserCommand.Reload -> BrowserViewCommand.Reload
    BrowserCommand.Stop -> BrowserViewCommand.Stop
}

private fun BrowserViewState.toDomain(previousUrl: String): BrowserPage = BrowserPage(
    url = url.ifEmpty { previousUrl },
    title = title,
    isLoading = isLoading,
    isBackAvailable = isBackAvailable,
    isForwardAvailable = isForwardAvailable,
    error = when (error) {
        null -> null
        BrowserViewError.LoadFailed -> BrowserError.LoadFailed
        BrowserViewError.UnsupportedAddress -> BrowserError.UnsupportedAddress
        BrowserViewError.EngineUnavailable -> BrowserError.EngineUnavailable
    },
)
