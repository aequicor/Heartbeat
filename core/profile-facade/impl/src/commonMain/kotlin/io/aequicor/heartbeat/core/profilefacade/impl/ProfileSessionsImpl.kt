package io.aequicor.heartbeat.core.profilefacade.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ActiveProfileStorage
import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Callers usually live inside the profile (a "sign out" button in a feature store), so closing the profile
 * cancels the caller. Hence every operation first does all suspending work (storage), then switches the
 * sessions synchronously — no suspension point after the old scope is closed, and observers of [active]
 * never see an intermediate `null` while switching profiles.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ProfileSessionsImpl(
    private val graphs: ProfileGraph.Factory,
    private val scopes: ScopeFactory,
    @ForScope(AppScope::class) private val appScope: ScopeHandle,
    private val storage: ActiveProfileStorage,
) : ProfileSessions {

    private val log = Log.tag("ProfileSessions")
    private val mutex = Mutex()
    private val state = MutableStateFlow<ProfileSession?>(null)
    private var owned: OwnedScope? = null

    override val active: StateFlow<ProfileSession?> = state.asStateFlow()

    override suspend fun restore(): ProfileSession? = mutex.withLock {
        state.value?.let { current ->
            log.i { "restore: profile ${current.id.value} is already active" }
            return current
        }
        val id = storage.read()
        if (id == null) {
            log.i { "restore: no active profile" }
            null
        } else {
            createSession(id).also(::replaceLocked).first.also { log.i { "restore: profile ${id.value} reopened" } }
        }
    }

    override suspend fun open(id: ProfileId): ProfileSession = mutex.withLock {
        state.value?.takeIf { it.id == id }?.let { current ->
            log.d { "open: profile ${id.value} is already active" }
            return current
        }
        storage.write(id)
        createSession(id).also(::replaceLocked).first
    }

    override suspend fun close() = mutex.withLock {
        storage.write(null)
        replaceLocked(null)
    }

    /** Synchronous: publishes [next] (or signs out when `null`), then closes the previous profile scope. */
    private fun replaceLocked(next: Pair<ProfileSession, OwnedScope>?) {
        val previous = state.value
        val previousScope = owned
        owned = next?.second
        state.value = next?.first
        previousScope?.close()
        log.i { "profile session: ${previous?.id?.value ?: "none"} -> ${next?.first?.id?.value ?: "none"}" }
    }

    private fun createSession(id: ProfileId): Pair<ProfileSession, OwnedScope> {
        val scope = scopes.child(appScope, PROFILE_SCOPE_NAME)
        val graph = try {
            graphs.create(id, scope)
        } catch (e: CancellationException) {
            scope.close()
            throw e
        } catch (e: Exception) {
            log.e(e) { "failed to create the graph of profile ${id.value}" }
            scope.close()
            throw e
        }
        return ProfileSession(id, graph) to scope
    }

    private companion object {
        const val PROFILE_SCOPE_NAME = "profile"
    }
}
