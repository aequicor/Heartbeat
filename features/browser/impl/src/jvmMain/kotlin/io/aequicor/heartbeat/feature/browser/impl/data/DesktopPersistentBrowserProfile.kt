package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.browser.impl.domain.DesktopBrowserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cef.CefRequestContextSettings
import org.cef.browser.CefRequestContext
import kotlin.coroutines.resume

/**
 * Owns persistent Chromium storage independently of the presentation layer and active profile job.
 * A failed [clearData] may be retried; until a clear succeeds the profile does not reopen Chromium storage,
 * and the on-disk pending marker makes the next open finish the deletion even after a restart.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopPersistentBrowserProfile(
    private val dispatchers: DispatcherProvider,
    @ForScope(ProfileScope::class) scope: ScopeHandle,
    @ForScope(AppScope::class) private val appScope: ScopeHandle,
    @ForScope(ProfileScope::class) stores: DataStores,
    profiles: DesktopBrowserProfiles,
) : DesktopBrowserProfile {
    private val log = Log.tag("PersistentBrowserProfile")
    private val monitor = Any()
    private val contextLock = Mutex()
    private val shutdownLock = Mutex()
    private val profileId = when (val owner = stores.owner) {
        is StorageOwner.Profile -> owner.id
        StorageOwner.App -> error("Persistent browser storage requires profile-owned DataStores")
    }
    private val files = profiles.files(profileId)
    private var anchor: CefRequestContext? = null
    private var closeSurfaces: (suspend () -> Unit)? = null
    private var clearJob: Deferred<Unit>? = null
    private var isClearing = false
    private val lifetime = profiles.register(profileId, ::shutdown)

    init {
        scope.onClose(lifetime::close)
    }

    override val isAvailable: Boolean
        get() = synchronized(monitor) { !lifetime.isClosed && !isClearing }

    override fun attach(closeSurfaces: suspend () -> Unit) {
        synchronized(monitor) {
            check(isAvailable && this.closeSurfaces == null) { "Browser profile cannot attach presentation" }
            this.closeSurfaces = closeSurfaces
        }
    }

    override suspend fun <T> useContext(block: (CefRequestContext) -> T): T {
        lifetime.awaitAvailable()
        return contextLock.withLock {
            check(isAvailable) { "Browser profile is unavailable" }
            // Store the context before cancellation can discard a newly allocated native resource.
            withContext(NonCancellable) { block(context()) }
        }
    }

    override suspend fun clearData() {
        val request = synchronized(monitor) {
            check(!lifetime.isClosed) { "Browser profile is closed" }
            // A running clear is shared; a finished one (successful or failed) is forgotten, so a retry starts anew.
            clearJob ?: appScope.coroutineScope.async(NonCancellable, start = CoroutineStart.LAZY) {
                clearStorage()
            }.also { job ->
                isClearing = true
                clearJob = job
                job.invokeOnCompletion { error ->
                    if (error != null && error !is CancellationException) {
                        log.e(error.redactedFailure()) { "Browser data cleanup failed; it may be retried" }
                    }
                }
            }
        }
        request.start()
        awaitBrowserTeardown(request)
    }

    /** Failures propagate to every waiting caller and are logged once when the clear job completes. */
    private suspend fun clearStorage() {
        var isComplete = false
        try {
            log.i { "Clearing persistent browser data" }
            lifetime.awaitPreviousClosed()
            shutdownLock.withLock {
                closeResources()
                files.delete(dispatchers)
            }
            lifetime.storageCleared()
            isComplete = true
            log.i { "Persistent browser data cleared" }
        } finally {
            if (!isComplete) lifetime.invalidateStorage()
            synchronized(monitor) {
                isClearing = false
                clearJob = null
            }
        }
    }

    private suspend fun context(): CefRequestContext {
        anchor?.let { return it }
        files.open(dispatchers)
        return checkNotNull(
            CefRequestContext.createContext(
                CefRequestContextSettings().apply {
                    cache_path = files.directory.absolutePath
                    persist_session_cookies = true
                },
                null,
            ),
        ) { "Persistent browser context is unavailable" }.also { anchor = it }
    }

    private suspend fun shutdown() {
        val clearing = synchronized(monitor) { clearJob }
        try {
            // Clear errors have already reached its caller. Native closure must still allow app exit;
            // the lifetime's shared storage gate continues blocking later profile generations.
            clearing?.join()
        } finally {
            withContext(NonCancellable) {
                shutdownLock.withLock { closeResources() }
            }
        }
    }

    private suspend fun closeResources() {
        synchronized(monitor) { closeSurfaces }?.invoke()
        contextLock.withLock {
            anchor?.let { context ->
                suspendCancellableCoroutine { continuation ->
                    context.dispose { continuation.resume(Unit) }
                }
                anchor = null
            }
        }
    }
}
