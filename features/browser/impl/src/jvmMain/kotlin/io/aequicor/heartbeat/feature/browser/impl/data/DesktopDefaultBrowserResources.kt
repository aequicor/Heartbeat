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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.CefBuildInfo
import me.friwi.jcefmaven.EnumOS
import me.friwi.jcefmaven.EnumPlatform
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefSettings
import java.io.File

/**
 * Extracts the bundled, versioned native archive inside app data on IO. No download fallback exists.
 * CEF stays alive until all profile contexts close; each profile owns persistent Chromium storage.
 * Failed extraction may be retried with a new builder (CefAppBuilder retains a failed building flag).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopDefaultBrowserResources(
    private val storageRoot: StorageRoot,
    private val dispatchers: DispatcherProvider,
    private val profiles: DesktopBrowserProfiles,
    @ForScope(AppScope::class) private val scope: ScopeHandle,
) : DesktopBrowserResources {
    private val log = Log.tag("DesktopBrowserResources")
    private val mutex = Mutex()
    private var initialization: Deferred<CefApp>? = null
    private var shutdown: Deferred<Unit>? = null
    private val terminated = CompletableDeferred<Unit>()

    @Volatile
    private var nativeApp: CefApp? = null

    init {
        scope.onClose {
            scope.coroutineScope.launch(NonCancellable) {
                try {
                    shutdown()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error.desktopBrowserFailure()) { "Desktop browser runtime shutdown failed" }
                }
            }
        }
    }

    override suspend fun app(): CefApp = mutex.withLock {
        check(shutdown == null) { "Browser runtime is closing" }
        initialization?.takeUnless { it.isCancelled } ?: scope.coroutineScope.async(dispatchers.io) {
            initialize()
        }.also { initialization = it }
    }.await()

    override suspend fun shutdown() {
        val request = mutex.withLock {
            shutdown ?: scope.coroutineScope.async(NonCancellable + dispatchers.io) {
                profiles.closeAll()
                // A cancelled initializer can still be finishing native startup on another thread.
                initialization?.join()
                nativeApp?.let { app ->
                    withContext(dispatchers.main) { app.dispose() }
                    terminated.await()
                }
                Unit
            }.also { shutdown = it }
        }
        awaitBrowserTeardown(request)
    }

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
            setAppHandler(object : MavenCefAppHandlerAdapter() {
                override fun stateHasChanged(state: CefApp.CefAppState) {
                    if (state == CefApp.CefAppState.TERMINATED) terminated.complete(Unit)
                }
            })
            cefSettings.useBundledBrowserPaths(directory, platform.os)
            cefSettings.windowless_rendering_enabled = false
            // CEF 152 Chrome mode only persists root_cache_path itself or its immediate children.
            cefSettings.root_cache_path = profiles.rootDirectory.absolutePath
            cefSettings.cache_path = ""
            cefSettings.persist_session_cookies = false
            cefSettings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE
            addJcefArgs("--disable-extensions", "--disable-background-networking")
        }
        val app = builder.build()
        nativeApp = app
        // createClient triggers CEF initialization and marshals native startup to EDT internally.
        app.createClient().dispose()
        check(CefApp.getState() == CefApp.CefAppState.INITIALIZED) { "Browser runtime initialization failed" }
        return app
    }
}

/**
 * Pins Windows subprocesses and resources to the same bundle as the loaded native libraries.
 * JCEF otherwise finds the first jcef.dll in java.library.path, which may belong to the JBR SDK.
 * On macOS jcefmaven already supplies the absolute helper and framework paths from its bundle.
 */
internal fun CefSettings.useBundledBrowserPaths(directory: File, os: EnumOS) {
    if (os != EnumOS.WINDOWS) return
    browser_subprocess_path = File(directory, "jcef_helper.exe").absolutePath
    resources_dir_path = directory.absolutePath
    locales_dir_path = File(directory, "locales").absolutePath
}
