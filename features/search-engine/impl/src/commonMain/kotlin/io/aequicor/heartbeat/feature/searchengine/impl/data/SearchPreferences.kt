package io.aequicor.heartbeat.feature.searchengine.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.booleanKey
import io.aequicor.heartbeat.core.datastore.stringKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.searchengine.api.DEFAULT_QUERIT_HOST
import io.aequicor.heartbeat.feature.searchengine.api.SearchConfiguration
import io.aequicor.heartbeat.feature.searchengine.api.SearchConnection
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.api.SearchProvider
import io.aequicor.heartbeat.feature.searchengine.api.SearchSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val storeSpec = KeyValueSpec("search_engine_settings")
private val searchHost = stringKey("search_host")
private val contentsHost = stringKey("contents_host")
private val searchProvider = stringKey("search_provider")
private val contentsProvider = stringKey("contents_provider")
private val preferNative = booleanKey("prefer_native")

/** Persistent non-secret settings and separate protected key slots of the active profile. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<SearchOptions>())
@Inject
internal class SearchPreferences(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val secrets: SecretStore,
) : SearchOptions {
    private val store = stores.keyValue(storeSpec)
    private val log = Log.tag("SearchPreferences")
    private val mutex = Mutex()

    override suspend fun read(): SearchSettings = SearchSettings(
        search = SearchConnection(
            provider = provider(SearchOperation.Search),
            host = host(SearchOperation.Search),
            hasKey = hasKey(SearchOperation.Search),
        ),
        contents = SearchConnection(
            provider = provider(SearchOperation.Contents),
            host = host(SearchOperation.Contents),
            hasKey = hasKey(SearchOperation.Contents),
        ),
        isNativePreferred = store.get(preferNative) ?: true,
    )

    override suspend fun setHost(operation: SearchOperation, host: String) {
        val normalized = validHost(host)
        log.i { "Saving $operation host" }
        store.set(if (operation == SearchOperation.Search) searchHost else contentsHost, normalized)
    }

    override suspend fun setProvider(operation: SearchOperation, provider: SearchProvider) {
        log.i { "Saving $operation provider: $provider" }
        store.set(if (operation == SearchOperation.Search) searchProvider else contentsProvider, provider.name)
    }

    override suspend fun setKey(operation: SearchOperation, key: Secret?) {
        mutex.withLock {
            val usage = usage(operation)
            val identifier = key(operation)
            // The key value itself is never logged.
            log.i { if (key == null) "Removing $operation key" else "Saving $operation key" }
            if (key == null) {
                secrets.bind(usage, null)
                secrets.remove(identifier)
            } else {
                // A pasted key often carries surrounding whitespace, which the service rejects as a bad key forever.
                val normalized = key.reveal { chars -> chars.trimmed() }
                if (normalized.isEmpty()) throw SearchException(SearchFailure.InvalidInput)
                Secret(normalized).use { trimmed -> secrets.write(identifier, trimmed) }
                secrets.bind(usage, identifier)
            }
        }
    }

    override suspend fun setPreferNative(enabled: Boolean) {
        log.i { "Saving prefer native: $enabled" }
        store.set(preferNative, enabled)
    }

    override suspend fun host(operation: SearchOperation): String =
        store.get(if (operation == SearchOperation.Search) searchHost else contentsHost) ?: DEFAULT_QUERIT_HOST

    private suspend fun provider(operation: SearchOperation): SearchProvider =
        store.get(if (operation == SearchOperation.Search) searchProvider else contentsProvider)
            ?.let { saved -> SearchProvider.entries.firstOrNull { it.name == saved } } ?: SearchProvider.Querit

    override suspend fun nativePreferred(): Boolean = store.get(preferNative) ?: true

    override suspend fun credential(operation: SearchOperation): Secret =
        secrets.readFor(usage(operation)) ?: throw SearchException(SearchFailure.NotConfigured)

    private suspend fun hasKey(operation: SearchOperation): Boolean =
        secrets.readFor(usage(operation))?.use { true } ?: false

    private fun key(operation: SearchOperation) = SecretKey("search_engine_${operation.name.lowercase()}")
    private fun usage(operation: SearchOperation) = SecretUsage("search_engine", "querit", operation.name.lowercase())
}

internal interface SearchOptions {
    suspend fun read(): SearchSettings
    suspend fun setProvider(operation: SearchOperation, provider: SearchProvider)
    suspend fun setHost(operation: SearchOperation, host: String)
    suspend fun setKey(operation: SearchOperation, key: Secret?)
    suspend fun setPreferNative(enabled: Boolean)
    suspend fun host(operation: SearchOperation): String
    suspend fun nativePreferred(): Boolean
    suspend fun credential(operation: SearchOperation): Secret
}

@SingleIn(ProfileScope::class)
@dev.zacsweers.metro.ContributesBinding(ProfileScope::class)
@Inject
internal class SearchConfigurationImpl(private val preferences: SearchOptions, private val querit: QueritApi) :
    SearchConfiguration {
    override suspend fun read(): SearchSettings = preferences.read()
    override suspend fun setProvider(operation: SearchOperation, provider: SearchProvider) = preferences.setProvider(
        operation,
        provider,
    )
    override suspend fun setHost(operation: SearchOperation, host: String) = preferences.setHost(operation, host)
    override suspend fun setKey(operation: SearchOperation, key: Secret?) = preferences.setKey(operation, key)
    override suspend fun setPreferNative(enabled: Boolean) = preferences.setPreferNative(enabled)
    override suspend fun check(operation: SearchOperation) {
        when (operation) {
            SearchOperation.Search -> querit.search("Querit", 1)
            SearchOperation.Contents -> querit.fetch("https://www.querit.ai/")
        }
    }
}

/** Copies without leading and trailing whitespace; no String of the secret value is created. */
private fun CharArray.trimmed(): CharArray {
    val first = indexOfFirst { !it.isWhitespace() }
    if (first == -1) return CharArray(0)
    return copyOfRange(first, indexOfLast { !it.isWhitespace() } + 1)
}

internal fun validHost(host: String): String {
    val normalized = host.trim().trimEnd('/')
    val hasHttpsOrigin = normalized.startsWith("https://") && normalized.length > "https://".length
    val authority = normalized.substringAfter("https://")
    val hasInvalidAuthority = authority.any { it == '/' || it == '@' || it == '?' || it == '#' }
    if (!hasHttpsOrigin || hasInvalidAuthority) {
        throw SearchException(SearchFailure.InvalidInput)
    }
    return normalized
}
