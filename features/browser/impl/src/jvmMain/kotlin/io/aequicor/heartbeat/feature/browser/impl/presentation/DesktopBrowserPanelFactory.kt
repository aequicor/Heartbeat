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
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserCommand
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserConnectivity
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserErrorPolicy
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserNativeResources
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserStorage
import io.aequicor.heartbeat.feature.browser.impl.domain.BrowserUrlResolver
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserProfile
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.domain.networkRecoveriesWhileEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cef.browser.CefRequestContext

/** Presentation entry point injected through the common native-resource port. */
internal interface DesktopBrowserPanelFactory : BrowserNativeResources {
    val networkRestored: Flow<Unit> get() = emptyFlow()
    val errorPagesEnabled: StateFlow<Boolean> get() = MutableStateFlow(false)
    fun rememberCertificateException(url: String): Unit = Unit
    fun hasCertificateException(url: String): Boolean = false
    fun isWithheldFromAgents(url: String): Boolean = hasCertificateException(url)
    val areCertificateDecisionsKnown: Boolean get() = true
    fun certificateExceptionUrls(): List<String> = emptyList()

    /**
     * The panel of [surface] whose documents [guard] restricts for an agent owner. The first creation of a surface
     * fixes its guard and its [storage]: the session creates each tab's panel with the tab's guard and storage before
     * any view can ask for it, and any other surface belongs to the user. [onShortcut] receives tab keys pressed
     * inside the page, on CEF's UI thread.
     */
    fun create(
        surface: BrowserSurface,
        guard: DesktopBrowserAgentGuard,
        openTab: ((BrowserCommand.Load) -> Unit)? = null,
        onShortcut: ((BrowserShortcut) -> Unit)? = null,
        storage: BrowserStorage = BrowserStorage.User,
    ): DesktopBrowserPanel
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
    errorPolicy: BrowserErrorPolicy,
    connectivity: BrowserConnectivity,
) : DesktopBrowserPanelFactory {
    override val errorPagesEnabled = errorPolicy.observe()
        .stateIn(scope.coroutineScope, SharingStarted.Eagerly, false)
    override val networkRestored = errorPagesEnabled.networkRecoveriesWhileEnabled(connectivity::restored)
    private val log = Log.tag("DesktopBrowserPanelFactory")
    private val certificateExceptions = DesktopCertificateExceptions()
    override fun rememberCertificateException(url: String) = certificateExceptions.remember(url)
    override fun hasCertificateException(url: String): Boolean = certificateExceptions.contains(url)
    override fun isWithheldFromAgents(url: String): Boolean = certificateExceptions.isWithheldFromAgents(url)
    override val areCertificateDecisionsKnown: Boolean get() = certificateExceptions.areDecisionsKnown
    override fun certificateExceptionUrls(): List<String> = certificateExceptions.urls()

    private val monitor = Any()
    private val panels = mutableMapOf<BrowserSurface, DesktopBrowserPanel>()
    private val retired = mutableListOf<DesktopBrowserPanel>()

    /** The agent session each panel in [panels] and [retired] stores for, if any; guarded by [monitor]. */
    private val agentOwners = mutableMapOf<DesktopBrowserPanel, String>()

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
                val owner = synchronized(monitor) {
                    retired.remove(closing)
                    agentOwners.remove(closing)
                }
                owner?.let { releaseStorage(it) }
            }
        }
    }

    /** The last tab of an agent session took its pages with it: the session's storage goes too. */
    private suspend fun releaseStorage(owner: String) {
        try {
            profile.releaseAgentContext(owner) { synchronized(monitor) { owner in agentOwners.values } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Agent browser session storage could not be discarded" }
        }
    }

    override fun create(
        surface: BrowserSurface,
        guard: DesktopBrowserAgentGuard,
        openTab: ((BrowserCommand.Load) -> Unit)?,
        onShortcut: ((BrowserShortcut) -> Unit)?,
        storage: BrowserStorage,
    ): DesktopBrowserPanel = synchronized(monitor) {
        check(profile.isAvailable) { "Browser profile is unavailable" }
        panels.getOrPut(surface) {
            DesktopBrowserPanel(
                surface,
                scope.coroutineScope,
                dispatchers,
                resources.preparation,
            ) { isReleased, changed ->
                val app = resources.app()
                val engine = { shared: CefRequestContext ->
                    check(!isReleased()) { "Browser surface is closed" }
                    DesktopBrowserEngine(
                        app,
                        dispatchers,
                        urlResolver,
                        isReleased,
                        changed,
                        guard = guard,
                        openTab = openTab,
                        onShortcut = onShortcut,
                        certificateExceptions = certificateExceptions,
                    ) {
                        checkNotNull(CefRequestContext.createContext(shared, it))
                    }
                }
                when (storage) {
                    BrowserStorage.User -> profile.useContext(engine)
                    is BrowserStorage.Agent -> profile.useAgentContext(storage.owner, engine)
                }
            }.also { panel -> (storage as? BrowserStorage.Agent)?.let { agentOwners[panel] = it.owner } }
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
        synchronized(monitor) {
            retired.removeAll(closing.toSet())
            // The profile discards the agent sessions' storage itself after the surfaces are closed.
            agentOwners.clear()
        }
        certificateExceptions.clear()
    }
}

/**
 * UI receives presentation only, without knowing the runtime's filesystem or bootstrap implementation. A session tab
 * already has its panel and keeps its guard; any other surface belongs to the user and is unrestricted.
 */
internal fun BrowserSurface.createDesktopPanel(): DesktopBrowserPanel =
    checkNotNull(nativeResources as? DesktopBrowserPanelFactory) { "Desktop browser factory is not bound" }
        .create(this, DesktopBrowserAgentGuard())
