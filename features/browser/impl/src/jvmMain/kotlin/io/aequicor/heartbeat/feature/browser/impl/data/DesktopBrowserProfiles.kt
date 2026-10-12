package io.aequicor.heartbeat.feature.browser.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.ProfileStorageCleaner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.browser.impl.domain.desktopBrowserFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import java.io.File
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes native profile generations across synchronous DI scope closure and asynchronous CEF disposal. */
@SingleIn(AppScope::class)
@Inject
internal class DesktopBrowserProfiles(
    @ForScope(AppScope::class) scope: ScopeHandle,
    private val dispatchers: DispatcherProvider,
    @ForScope(AppScope::class) stores: Lazy<DataStores>,
) {
    private val lifetimes = DesktopBrowserLifetimes(scope.coroutineScope)

    // Lazy breaks StoreRegistry -> ProfileStorageCleaner -> browser -> App DataStores construction.
    val rootDirectory: File by lazy { File(stores.value.filesDirectory("browser_profiles")) }

    fun files(id: ProfileId): BrowserProfileFiles {
        require(id.value.isNotEmpty()) { "Profile id is required" }
        val encoded = HexFormat.of().formatHex(id.value.encodeToByteArray())
        return BrowserProfileFiles(File(rootDirectory, encoded), rootDirectory)
    }

    fun register(id: ProfileId, dispose: suspend () -> Unit): DesktopBrowserLifetime = lifetimes.register(id, dispose)

    suspend fun wipeProfile(id: ProfileId) {
        lifetimes.awaitClosed(id)
        files(id).delete(dispatchers)
        lifetimes.storageWiped(id)
    }

    suspend fun closeAll() = lifetimes.closeAll()
}

/** Pure lifetime coordinator; failed shutdown remains a barrier until application restart. */
internal class DesktopBrowserLifetimes(private val scope: CoroutineScope) {
    private val monitor = Any()
    private val owners = mutableMapOf<ProfileId, DesktopBrowserLifetime>()
    private var isClosed = false

    fun register(id: ProfileId, dispose: suspend () -> Unit): DesktopBrowserLifetime = synchronized(monitor) {
        check(!isClosed) { "Browser runtime is closing" }
        val previous = owners[id]
        DesktopBrowserLifetime(scope, previous, previous?.reuseAllowed ?: AtomicBoolean(true), dispose) { closed ->
            synchronized(monitor) {
                if (owners[id] === closed && closed.reuseAllowed.get()) owners.remove(id)
            }
        }.also { owners[id] = it }
    }

    fun storageWiped(id: ProfileId) {
        synchronized(monitor) {
            check(owners[id]?.isFinished != false) { "Browser profile has not closed" }
            owners.remove(id)
        }
    }

    suspend fun awaitClosed(id: ProfileId) {
        val owner = synchronized(monitor) { owners[id] }
        if (owner != null) {
            check(owner.isClosed) { "Browser profile is still open" }
            owner.awaitClosed()
        }
    }

    suspend fun closeAll() {
        val current = synchronized(monitor) {
            isClosed = true
            owners.values.toList()
        }
        current.forEach(DesktopBrowserLifetime::close)
        current.forEach { it.awaitClosed() }
    }
}

internal class DesktopBrowserLifetime(
    private val scope: CoroutineScope,
    previous: DesktopBrowserLifetime?,
    val reuseAllowed: AtomicBoolean,
    private val dispose: suspend () -> Unit,
    private val onClosed: (DesktopBrowserLifetime) -> Unit,
) {
    @Volatile
    private var previous = previous
    private val log = Log.tag("BrowserProfileLifetime")
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    val isClosed: Boolean get() = closing.get()
    val isFinished: Boolean get() = closed.isCompleted && !closed.isCancelled

    fun invalidateStorage() {
        reuseAllowed.set(false)
    }

    suspend fun awaitAvailable() {
        awaitPrevious()
        check(!isClosed) { "Browser profile is closed" }
    }

    suspend fun awaitPrevious() {
        previous?.awaitClosed()
        previous = null
        check(reuseAllowed.get()) { "Browser storage cleanup failed; wipe the profile before reopening" }
    }

    suspend fun awaitClosed() = closed.await()

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        previous?.close()
        // DI cancels its coroutine tree before running close hooks. Native teardown must finish anyway.
        scope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.awaitClosed()
                dispose()
                closed.complete(Unit)
                previous = null
                onClosed(this@DesktopBrowserLifetime)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error.desktopBrowserFailure()) { "Browser profile shutdown failed" }
                closed.completeExceptionally(error)
            } catch (error: LinkageError) {
                log.e(error.desktopBrowserFailure()) { "Browser native shutdown is unavailable" }
                closed.completeExceptionally(error)
            }
        }.invokeOnCompletion { error ->
            if (error != null) closed.completeExceptionally(error)
        }
    }
}

/** Regular profile files may only be removed after the last native generation has finished. */
@ContributesIntoSet(AppScope::class)
@Inject
internal class DesktopBrowserProfileCleaner(private val profiles: DesktopBrowserProfiles) : ProfileStorageCleaner {
    override suspend fun wipeProfile(id: ProfileId) = profiles.wipeProfile(id)
}
