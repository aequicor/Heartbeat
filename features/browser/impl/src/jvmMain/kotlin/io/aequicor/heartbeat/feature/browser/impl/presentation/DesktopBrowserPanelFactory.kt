package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources

/** Presentation entry point injected through the common native-resource port. */
internal interface DesktopBrowserPanelFactory : BrowserNativeResources {
    fun create(surface: BrowserSurface): DesktopBrowserPanel
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<BrowserNativeResources>())
@Inject
internal class DefaultDesktopBrowserPanelFactory(
    private val resources: DesktopBrowserResources,
    private val dispatchers: DispatcherProvider,
    @ForScope(AppScope::class) private val scope: ScopeHandle,
) : DesktopBrowserPanelFactory {
    override fun create(surface: BrowserSurface): DesktopBrowserPanel =
        DesktopBrowserPanel(surface, scope.coroutineScope, dispatchers) { isReleased, changed ->
            val app = resources.app()
            DesktopBrowserEngine(app, isReleased, changed)
        }
}

/** UI receives presentation only, without knowing the runtime's filesystem or bootstrap implementation. */
internal fun BrowserSurface.createDesktopPanel(): DesktopBrowserPanel =
    checkNotNull(nativeResources as? DesktopBrowserPanelFactory) { "Desktop browser factory is not bound" }.create(this)
