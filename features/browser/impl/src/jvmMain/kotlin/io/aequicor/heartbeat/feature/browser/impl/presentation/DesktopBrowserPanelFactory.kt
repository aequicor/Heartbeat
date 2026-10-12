package io.aequicor.heartbeat.feature.browser.impl.presentation

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserProfile
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import kotlinx.coroutines.launch
import org.cef.browser.CefRequestContext

/** Presentation entry point injected through the common native-resource port. */
internal interface DesktopBrowserPanelFactory : BrowserNativeResources {
    fun create(surface: BrowserSurface): DesktopBrowserPanel

    /**
     * The panel of a session tab whose main documents [guard] restricts for an agent owner. The first creation of a
     * surface fixes its guard; the production factory overrides this, test doubles may ignore the guard.
     */
    fun create(surface: BrowserSurface, guard: DesktopBrowserAgentGuard): DesktopBrowserPanel = create(surface)
    fun close(surface: BrowserSurface)

    /** Closes all surfaces and native context before deleting site data; failure prevents reuse. */
    suspend fun clearData()
}

@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<BrowserNativeResources>())
@Inject
internal class DefaultDesktopBrowserPanelFactory(
    private val resources: DesktopBrowserResources,
    private val dispatchers: DispatcherProvider,
    private val urlResolver: BrowserUrlResolver,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
    @ForScope(AppScope::class) private val appScope: ScopeHandle,
    private val profile: DesktopBrowserProfile,
) : DesktopBrowserPanelFactory {
    private val monitor = Any()
    private val panels = mutableMapOf<BrowserSurface, DesktopBrowserPanel>()
    private val retired = mutableListOf<DesktopBrowserPanel>()

    init {
        profile.attach(::closePanels)
    }

    override fun close(surface: BrowserSurface) {
        val closing = synchronized(monitor) {
            panels.remove(surface)?.also {
                retired += it
                it.release()
            }
        }
        if (closing != null) {
            appScope.coroutineScope.launch {
                closing.awaitClosed()
                synchronized(monitor) { retired.remove(closing) }
            }
        }
    }

    override fun create(surface: BrowserSurface): DesktopBrowserPanel = create(surface, DesktopBrowserAgentGuard())

    override fun create(surface: BrowserSurface, guard: DesktopBrowserAgentGuard): DesktopBrowserPanel =
        synchronized(monitor) {
            check(profile.isAvailable) { "Browser profile is unavailable" }
            panels.getOrPut(surface) {
                DesktopBrowserPanel(surface, scope.coroutineScope, dispatchers) { isReleased, changed ->
                    val app = resources.app()
                    profile.useContext { shared ->
                        check(!isReleased()) { "Browser surface is closed" }
                        DesktopBrowserEngine(app, dispatchers, urlResolver, isReleased, changed, guard = guard) {
                            checkNotNull(CefRequestContext.createContext(shared, it))
                        }
                    }
                }
            }
        }

    override suspend fun clearData() = profile.clearData()

    private suspend fun closePanels() {
        val closing = synchronized(monitor) {
            (panels.values + retired).distinct().also {
                panels.clear()
                retired.clear()
                retired += it
            }
        }
        closing.forEach(DesktopBrowserPanel::release)
        closing.forEach { it.awaitClosed() }
        synchronized(monitor) { retired.removeAll(closing.toSet()) }
    }
}

/** UI receives presentation only, without knowing the runtime's filesystem or bootstrap implementation. */
internal fun BrowserSurface.createDesktopPanel(): DesktopBrowserPanel =
    checkNotNull(nativeResources as? DesktopBrowserPanelFactory) { "Desktop browser factory is not bound" }.create(this)
