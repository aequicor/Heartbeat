package io.aequicor.heartbeat.feature.searchengine.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebSearch
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import kotlinx.coroutines.CancellationException

/** Profile-owned routing: a callable native feature is preferred, with one provider fallback. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class RoutedSearchEngine(private val preferences: SearchOptions, private val querit: QueritApi) :
    SearchEngine {
    private val log = Log.tag("SearchEngine")

    override suspend fun search(query: String, count: Int, native: EngineFeatures?): List<SearchResult> {
        if (query.isBlank() || count !in 1..MAX_RESULTS) throw SearchException(SearchFailure.InvalidInput)
        if (preferences.nativePreferred()) {
            val feature = (native?.resolve(NativeWebSearch) as? FeatureAccess.Available)?.feature
            if (feature != null) {
                try {
                    val result = feature.search(query, count)
                    val usable = result.filter { isPublicWebUrl(it.url) }.take(count)
                    if (usable.isNotEmpty()) return usable
                    log.w { "Native search returned no usable URLs" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Native search failed" }
                }
            }
        }
        return querit.search(query, count)
    }

    override suspend fun fetch(url: String, native: EngineFeatures?): ResourceContent {
        requirePublicWebUrl(url)
        val attempt = nativeAttempt(url, native)
        if (attempt.content != null) return attempt.content
        return try {
            querit.fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchException) {
            // The provider error speaks about the provider, while a structured native verdict speaks about
            // the requested URL and is what diagnoses the link, so the verdict wins when both paths fail.
            throw attempt.failure?.takeIf { it.details != null } ?: e
        }
    }

    /** One native attempt: its content on success, or the structured failure to prefer over a provider one. */
    private suspend fun nativeAttempt(url: String, native: EngineFeatures?): NativeAttempt {
        if (!preferences.nativePreferred()) return NativeAttempt(null, null)
        val feature = (native?.resolve(NativeWebFetch) as? FeatureAccess.Available)?.feature
            ?: return NativeAttempt(null, null)
        return try {
            val result = feature.fetch(url)
            if (isPublicWebUrl(result.url) && result.text.isNotBlank()) {
                NativeAttempt(result, null)
            } else {
                log.w { "Native page reader returned no usable content" }
                NativeAttempt(null, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchException) {
            log.w(e) { "Native page reader failed" }
            NativeAttempt(null, e)
        } catch (e: Exception) {
            log.w(e) { "Native page reader failed" }
            NativeAttempt(null, null)
        }
    }

    private fun invalidUrl(url: String): SearchException = SearchException(
        SearchFailure.InvalidInput,
        "invalid_url — url=$url — not a public http(s) address",
    )

    private fun requirePublicWebUrl(url: String) {
        if (!isPublicWebUrl(url)) throw invalidUrl(url)
    }

    /** Result of one native read: exactly one of the fields is non-null after a completed attempt. */
    private data class NativeAttempt(val content: ResourceContent?, val failure: SearchException?)

    private companion object {
        const val MAX_RESULTS = 20
    }
}
