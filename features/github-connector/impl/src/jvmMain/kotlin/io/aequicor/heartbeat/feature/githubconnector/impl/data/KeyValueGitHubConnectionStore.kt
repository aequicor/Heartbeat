package io.aequicor.heartbeat.feature.githubconnector.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnection
import io.aequicor.heartbeat.feature.githubconnector.api.GitHubConnectionSource
import io.aequicor.heartbeat.feature.githubconnector.impl.domain.GitHubConnectionStore

/** The connection in the profile key-value store: one JSON value without any credential. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class KeyValueGitHubConnectionStore(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : GitHubConnectionStore {
    private val log = Log.tag("KeyValueGitHubConnectionStore")
    private val store by lazy { stores.keyValue(SPEC) }

    override suspend fun load(): GitHubConnection? = store.get(CONNECTION)

    override suspend fun save(connection: GitHubConnection) {
        log.d { "write GitHub connection" }
        store.set(CONNECTION, connection)
    }

    override suspend fun clear() {
        log.d { "remove GitHub connection" }
        store.remove(CONNECTION)
    }

    private companion object {
        val SPEC = KeyValueSpec("github_connector")
        val CONNECTION = jsonKey("connection", GitHubConnection.serializer())
    }
}

/** What the PR tools read; the same value the connector screen shows. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class StoredGitHubConnectionSource(private val store: GitHubConnectionStore) : GitHubConnectionSource {
    override suspend fun current(): GitHubConnection? = store.load()
}
