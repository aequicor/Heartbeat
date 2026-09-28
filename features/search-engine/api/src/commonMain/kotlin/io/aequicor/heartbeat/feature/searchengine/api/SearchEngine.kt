package io.aequicor.heartbeat.feature.searchengine.api

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures

/** A source returned by web search. The URL is the citation address. */
public data class SearchResult(public val url: String, public val title: String, public val snippet: String)

/** Clean text of one resource, together with its original citation address. */
public data class ResourceContent(public val url: String, public val title: String?, public val text: String)

/**
 * Search and page retrieval performed by the selected provider or a callable native engine feature.
 * This service feature has no business state machine: each request completes independently.
 */
public interface SearchEngine {
    /** Finds at most [count] resources; an invalid or empty response fails explicitly. */
    public suspend fun search(query: String, count: Int = 5, native: EngineFeatures? = null): List<SearchResult>

    /** Reads one HTTP(S) resource. Empty or unusable content fails explicitly. */
    public suspend fun fetch(url: String, native: EngineFeatures? = null): ResourceContent
}

/** Optional native operation. The host routes its failure or unusable result to the configured provider. */
public interface NativeWebSearch : EngineFeature {
    /** Searches through a callable engine-native operation. */
    public suspend fun search(query: String, count: Int): List<SearchResult>

    /** Feature key used by the profile router. */
    public companion object : EngineFeatureKey<NativeWebSearch>(
        EngineFeatureId("web.search"),
        NativeWebSearch::class,
    )
}

/** Optional native page reader, independent of native search support. */
public interface NativeWebFetch : EngineFeature {
    /** Reads a URL through a callable engine-native operation. */
    public suspend fun fetch(url: String): ResourceContent

    /** Feature key used by the profile router. */
    public companion object : EngineFeatureKey<NativeWebFetch>(
        EngineFeatureId("web.fetch"),
        NativeWebFetch::class,
    )
}

/** Future provider IDs can be added without changing tool input and output. */
public enum class SearchProvider { Querit, }

/** Search and Contents have separate provider configuration and credentials. */
public enum class SearchOperation { Search, Contents }

/** Non-secret settings for one operation. */
public data class SearchConnection(
    public val provider: SearchProvider = SearchProvider.Querit,
    public val host: String = DEFAULT_QUERIT_HOST,
    public val hasKey: Boolean = false,
)

/** Settings of the active profile. */
public data class SearchSettings(
    public val search: SearchConnection = SearchConnection(),
    public val contents: SearchConnection = SearchConnection(),
    public val preferNative: Boolean = true,
)

/** Profile-owned configuration. A supplied [Secret] remains caller-owned and must be closed by the caller. */
public interface SearchConfiguration {
    /** Reads both non-secret connection settings and key-presence flags. */
    public suspend fun read(): SearchSettings

    /** Selects the provider for one operation. */
    public suspend fun setProvider(operation: SearchOperation, provider: SearchProvider)

    /** Saves the HTTPS API origin for one operation. */
    public suspend fun setHost(operation: SearchOperation, host: String)

    /** Replaces or removes the API key for one operation. */
    public suspend fun setKey(operation: SearchOperation, key: Secret?)

    /** Controls whether callable engine-native operations run first. */
    public suspend fun setPreferNative(enabled: Boolean)

    /** Makes one real minimal API call, which may consume provider quota. */
    public suspend fun check(operation: SearchOperation)
}

/** Safe classification of a search failure; external response bodies and secrets are never exposed. */
public enum class SearchFailure {
    NotConfigured,
    InvalidInput,
    Authentication,
    RateLimited,
    Connectivity,
    Timeout,
    InvalidResponse,
    Unavailable,
}

/** Typed expected search failure. */
public class SearchException(public val failure: SearchFailure) : Exception(failure.name)

public const val DEFAULT_QUERIT_HOST: String = "https://api.querit.ai"
