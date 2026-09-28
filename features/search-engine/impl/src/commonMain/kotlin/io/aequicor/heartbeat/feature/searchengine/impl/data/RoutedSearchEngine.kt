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
                    val usable = result.filter { validResourceUrl(it.url) }.take(count)
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
        if (!validResourceUrl(url)) throw SearchException(SearchFailure.InvalidInput)
        if (preferences.nativePreferred()) {
            val feature = (native?.resolve(NativeWebFetch) as? FeatureAccess.Available)?.feature
            if (feature != null) {
                try {
                    val result = feature.fetch(url)
                    if (validResourceUrl(result.url) && result.text.isNotBlank()) return result
                    log.w { "Native page reader returned no usable content" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Native page reader failed" }
                }
            }
        }
        return querit.fetch(url)
    }

    private companion object {
        const val MAX_RESULTS = 20
    }
}
