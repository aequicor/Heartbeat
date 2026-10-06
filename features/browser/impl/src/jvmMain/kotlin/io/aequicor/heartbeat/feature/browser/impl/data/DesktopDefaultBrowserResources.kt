package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.domain.desktopBrowserFailure
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.EnumPlatform
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefSettings
import java.io.File
import javax.swing.SwingUtilities

/**
 * Extracts the bundled, versioned native archive inside app data on IO. No download fallback exists.
 * CEF stays alive until AppScope closes; its clients and in-memory request contexts belong to views.
 * Failed extraction may be retried with a new builder (CefAppBuilder retains a failed building flag).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopDefaultBrowserResources(
    private val storageRoot: StorageRoot,
    private val dispatchers: DispatcherProvider,
    @ForScope(AppScope::class) private val scope: ScopeHandle,
) : DesktopBrowserResources {
    private val log = Log.tag("DesktopBrowserResources")
    private val mutex = Mutex()
    private var initialization: Deferred<CefApp>? = null

    override suspend fun app(): CefApp = mutex.withLock {
        initialization?.takeUnless { it.isCancelled } ?: scope.coroutineScope.async(dispatchers.io) {
            initialize()
        }.also { initialization = it }
    }.await()

    private fun initialize(): CefApp {
        val info = CefBuildInfo.fromClasspath()
        val platform = EnumPlatform.getCurrentPlatform()
        val archive = "/jcef-natives-${platform.identifier}-${info.releaseTag}.tar.gz"
        checkNotNull(CefApp::class.java.getResource(archive)) { "Bundled browser runtime is missing" }
        val directory = File(storageRoot.path(), "cache/browser/jcef/${info.releaseTag}/${platform.identifier}")
        val builder = CefAppBuilder().apply {
            setInstallDir(directory)
            setMirrors(emptyList())
            setProgressHandler { _, _ -> }
            setAppHandler(object : MavenCefAppHandlerAdapter() {})
            cefSettings.windowless_rendering_enabled = false
            cefSettings.root_cache_path = File(
                storageRoot.path(),
                "cache/browser/cef-${ProcessHandle.current().pid()}",
            ).path
            cefSettings.cache_path = ""
            cefSettings.persist_session_cookies = false
            cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE
            addJcefArgs("--disable-extensions", "--disable-background-networking")
        }
        val app = builder.build()
        scope.onClose {
            SwingUtilities.invokeLater {
                try {
                    app.dispose()
                } catch (error: IllegalStateException) {
                    log.w(error.desktopBrowserFailure()) { "Desktop browser runtime shutdown failed" }
                }
            }
        }
        // createClient triggers CEF initialization and marshals native startup to EDT internally.
        app.createClient().dispose()
        check(CefApp.getState() == CefApp.CefAppState.INITIALIZED) { "Browser runtime initialization failed" }
        return app
    }
}
