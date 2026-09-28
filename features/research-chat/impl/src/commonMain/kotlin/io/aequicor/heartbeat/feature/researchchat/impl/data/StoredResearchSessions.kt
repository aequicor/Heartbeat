package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchStorage
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

private val ResearchSpec = KeyValueSpec("research_chat_sessions")
private val SessionsKey = jsonKey("sessions", ListSerializer(ResearchSession.serializer()))

/** Profile storage owns permanence and cleanup; DataStores logs all reads and writes without their values. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredResearchSessions(
    @ForScope(ProfileScope::class) stores: DataStores,
) : ResearchStorage {
    private val store = stores.keyValue(ResearchSpec)
    private val mutex = Mutex()

    override fun observe() = store.observe(SessionsKey).map { it.orEmpty() }

    override suspend fun read(): List<ResearchSession> = store.get(SessionsKey).orEmpty()

    override suspend fun add(session: ResearchSession) = mutex.withLock {
        store.set(SessionsKey, listOf(session) + read())
    }

    override suspend fun update(id: String, transform: (ResearchSession) -> ResearchSession) = mutex.withLock {
        val sessions = read()
        check(sessions.any { it.id == id }) { "Unknown research session" }
        store.set(SessionsKey, sessions.map { if (it.id == id) transform(it) else it })
    }
}
