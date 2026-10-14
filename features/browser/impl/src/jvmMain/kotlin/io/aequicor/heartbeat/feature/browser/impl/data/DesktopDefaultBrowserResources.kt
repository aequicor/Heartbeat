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
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserResources
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopDocumentTeardown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
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
 * Shutdown disposes CEF even when a profile fails to close, reports every failure, and a failed shutdown
 * may be retried; a timed-out one keeps running and a retry awaits it.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopDefaultBrowserResources(
    private val storageRoot: StorageRoot,
    private val dispatchers: DispatcherProvider,
    private val profiles: DesktopBrowserProfiles,
    @ForScope(AppScope::class) private val scope: ScopeHandle,
    private val documents: DesktopDocumentTeardown = DesktopDocumentTeardown.None,
) : DesktopBrowserResources {
    private val log = Log.tag("DesktopBrowserResources")
    private val mutex = Mutex()
    private var initialization: Deferred<CefApp>? = null
    private var shutdown: Deferred<Unit>? = null
    private val terminated = CompletableDeferred<Unit>()

    // Owned by this app singleton and never cancelled: DI cancels the app scope before close hooks run, while
    // native teardown must still finish. The process exit ends it.
    private val teardownScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    @Volatile
    private var nativeApp: CefApp? = null

    init {
        scope.onClose {
            teardownScope.launch {
                try {
                    shutdown()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log.e(error.redactedFailure()) { "Desktop browser runtime shutdown failed" }
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
            // A failed attempt is not cached: the retry closes what remains and reports again.
            shutdown?.takeUnless { it.isCancelled } ?: teardownScope.async { teardown() }.also { shutdown = it }
        }
        awaitBrowserTeardown(request)
    }

    /** Disposes the runtime even when documents or a profile fail to close, then reports all failures together. */
    private suspend fun teardown() {
        log.v { "Closing document browsers before runtime shutdown" }
        val documentFailure = closeDocuments()
        log.v { "Closing browser profiles before runtime shutdown" }
        val profileFailure = closeProfiles()
        log.v { "Disposing browser runtime after resource closure" }
        // The runtime's own failure leads; the earlier ones are attached to it.
        val failures = listOfNotNull(disposeRuntime(), profileFailure, documentFailure)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        log.v { "Browser runtime shutdown completed" }
    }

    private suspend fun closeDocuments(): Exception? = try {
        documents.closeDocuments()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.e(error.redactedFailure()) { "Document browsers failed to close; disposing the runtime anyway" }
        error
    }

    private suspend fun closeProfiles(): Exception? = try {
        profiles.closeAll()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.e(error.redactedFailure()) { "Browser profiles failed to close; disposing the runtime anyway" }
        error
    }

    private suspend fun disposeRuntime(): Exception? = try {
        // A cancelled initializer can still be finishing native startup on another thread.
        initialization?.join()
        nativeApp?.let { app ->
            // CefApp.dispose ignores repeated calls once the runtime is shutting down or terminated.
            withContext(dispatchers.main) { app.dispose() }
            terminated.await()
        }
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.e(error.redactedFailure()) { "Browser runtime disposal failed" }
        error
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
            // Chrome's standalone first-run dialog otherwise blocks CefInitialize on fresh Linux profiles.
            addJcefArgs("--disable-extensions", "--disable-background-networking", "--no-first-run")
            if (platform.os == EnumOS.LINUX) {
                // JCEF reparents the browser with XReparentWindow; Wayland sessions must use XWayland too.
                addJcefArgs("--ozone-platform=x11")
            }
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
 * Pins Windows and Linux subprocesses and resources to the same bundle as the loaded native libraries.
 * JCEF otherwise discovers paths relative to Java or another runtime on java.library.path.
 * On macOS jcefmaven already supplies the absolute helper and framework paths from its bundle.
 */
internal fun CefSettings.useBundledBrowserPaths(directory: File, os: EnumOS) {
    val helper = when (os) {
        EnumOS.WINDOWS -> "jcef_helper.exe"
        EnumOS.LINUX -> "jcef_helper"
        else -> return
    }
    browser_subprocess_path = File(directory, helper).absolutePath
    resources_dir_path = directory.absolutePath
    locales_dir_path = File(directory, "locales").absolutePath
}
