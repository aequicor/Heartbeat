package io.aequicor.heartbeat.feature.searchengine.impl

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebSearch
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.api.SearchProvider
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import io.aequicor.heartbeat.feature.searchengine.api.SearchSettings
import io.aequicor.heartbeat.feature.searchengine.impl.data.QueritApi
import io.aequicor.heartbeat.feature.searchengine.impl.data.RoutedSearchEngine
import io.aequicor.heartbeat.feature.searchengine.impl.data.SearchConfigurationImpl
import io.aequicor.heartbeat.feature.searchengine.impl.data.SearchOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchEngineTest {
    private val options = TestOptions()
    private var calls = 0

    @Test fun `search and contents use separate hosts and credentials`() = runTest {
        val api = api { request ->
            calls++
            if (request.url.host == "search.example") {
                assertEquals("Bearer search-secret", request.headers[HttpHeaders.Authorization])
                respond(
                    """{"results":{"result":[{"url":"https://example.com/a","title":"A","snippet":"B"}]}}""",
                    headers = jsonHeaders,
                )
            } else {
                assertEquals("content.example", request.url.host)
                assertEquals("Bearer contents-secret", request.headers[HttpHeaders.Authorization])
                respond(
                    """{"results":[{"url":"https://example.com/a","status":"success","content":"Page text"}]}""",
                    headers = jsonHeaders,
                )
            }
        }
        val router = RoutedSearchEngine(options, api)
        assertEquals("A", router.search("topic").single().title)
        assertEquals("Page text", router.fetch("https://example.com/a").text)
        assertEquals(2, calls)
    }

    @Test fun `native success skips Querit and empty native result falls back`() = runTest {
        val router = RoutedSearchEngine(
            options,
            api {
                calls++
                respond(SEARCH_RESPONSE, headers = jsonHeaders)
            },
        )
        val native = features(
            search = object : NativeWebSearch {
                override suspend fun search(query: String, count: Int) = listOf(
                    SearchResult("https://native.example", "Native", ""),
                )
            },
        )
        assertEquals("Native", router.search("topic", native = native).single().title)
        assertEquals(0, calls)
        val empty = features(
            search = object : NativeWebSearch {
                override suspend fun search(query: String, count: Int) = listOf(SearchResult("not a URL", "Bad", ""))
            },
        )
        assertEquals("A", router.search("topic", native = empty).single().title)
        assertEquals(1, calls)
    }

    @Test fun `failed native reader falls back without retrying the native call`() = runTest {
        val router = RoutedSearchEngine(
            options,
            api {
                calls++
                respond(CONTENTS_RESPONSE, headers = jsonHeaders)
            },
        )
        val native = features(
            fetch = object : NativeWebFetch {
                override suspend fun fetch(url: String): ResourceContent = error("offline")
            },
        )
        assertEquals("Page text", router.fetch("https://example.com/a", native).text)
        assertEquals(1, calls)
    }

    @Test fun `missing and failing native search each use one provider request`() = runTest {
        val router = RoutedSearchEngine(
            options,
            api {
                calls++
                respond(SEARCH_RESPONSE, headers = jsonHeaders)
            },
        )
        assertEquals("A", router.search("topic").single().title)
        val native = features(
            search = object : NativeWebSearch {
                override suspend fun search(query: String, count: Int): List<SearchResult> = error("native failed")
            },
        )
        assertEquals("A", router.search("topic", native = native).single().title)
        assertEquals(2, calls)
    }

    @Test fun `unusable native content and provider failure return a typed error`() = runTest {
        val router = RoutedSearchEngine(options, api { respond("{}", HttpStatusCode.ServiceUnavailable) })
        val native = features(
            fetch = object : NativeWebFetch {
                override suspend fun fetch(url: String) = ResourceContent(url, null, "")
            },
        )
        assertEquals(
            SearchFailure.Unavailable,
            assertFailsWith<SearchException> { router.fetch("https://example.com", native) }.failure,
        )
    }

    @Test fun `provider timeout is typed and never leaks transport text`() = runTest {
        val api = api { throw ConnectTimeoutException("private endpoint") }
        val error = assertFailsWith<SearchException> { api.search("topic", 1) }
        assertEquals(SearchFailure.Timeout, error.failure)
        assertEquals("Timeout", error.message)
    }

    @Test fun `cancellation never falls back`() = runTest {
        val router = RoutedSearchEngine(
            options,
            api {
                calls++
                respond(SEARCH_RESPONSE, headers = jsonHeaders)
            },
        )
        val native = features(
            search = object : NativeWebSearch {
                override suspend fun search(query: String, count: Int): List<SearchResult> =
                    throw CancellationException()
            },
        )
        assertFailsWith<CancellationException> { router.search("topic", native = native) }
        assertEquals(0, calls)
    }

    @Test fun `http authentication and malformed response are typed failures`() = runTest {
        val denied = api { respond("{}", HttpStatusCode.Unauthorized, jsonHeaders) }
        assertEquals(
            SearchFailure.Authentication,
            assertFailsWith<SearchException> { denied.search("topic", 1) }.failure,
        )
        val malformed = api { respond("{}", headers = jsonHeaders) }
        assertEquals(
            SearchFailure.InvalidResponse,
            assertFailsWith<SearchException> { malformed.search("topic", 1) }.failure,
        )
    }

    @Test fun `per-page crawl failure does not look like content`() = runTest {
        val api = api {
            respond(
                """{"results":[{"url":"https://example.com/a","status":"failed"}]}""",
                headers = jsonHeaders,
            )
        }
        assertEquals(
            SearchFailure.Unavailable,
            assertFailsWith<SearchException> { api.fetch("https://example.com/a") }.failure,
        )
    }

    @Test fun `connection checks issue independent minimal requests`() = runTest {
        val paths = mutableListOf<String>()
        val api = api { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/search")) {
                respond(SEARCH_RESPONSE, headers = jsonHeaders)
            } else {
                respond(CONTENTS_RESPONSE, headers = jsonHeaders)
            }
        }
        val configuration = SearchConfigurationImpl(options, api)
        configuration.check(SearchOperation.Search)
        configuration.check(SearchOperation.Contents)
        assertEquals(listOf("/v1/search", "/v1/contents"), paths)
    }

    private fun api(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ): QueritApi = QueritApi(HttpClient(MockEngine(handler)) { expectSuccess = true }, options)

    private class TestOptions : SearchOptions {
        override suspend fun read() = SearchSettings()
        override suspend fun setProvider(operation: SearchOperation, provider: SearchProvider) = Unit
        override suspend fun setHost(operation: SearchOperation, host: String) = Unit
        override suspend fun setKey(operation: SearchOperation, key: Secret?) = Unit
        override suspend fun setPreferNative(enabled: Boolean) = Unit
        override suspend fun host(operation: SearchOperation) = if (operation ==
            SearchOperation.Search
        ) {
            "https://search.example"
        } else {
            "https://content.example"
        }
        override suspend fun nativePreferred() = true
        override suspend fun credential(operation: SearchOperation) = Secret(
            if (operation ==
                SearchOperation.Search
            ) {
                "search-secret".toCharArray()
            } else {
                "contents-secret".toCharArray()
            },
        )
    }

    private fun features(search: NativeWebSearch? = null, fetch: NativeWebFetch? = null): EngineFeatures =
        object : EngineFeatures {
            @Suppress("UNCHECKED_CAST") // Keys are compared before the one narrow test-only cast.
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = when (key) {
                NativeWebSearch -> search?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
                NativeWebFetch -> fetch?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
                else -> FeatureAccess.Unsupported
            } as FeatureAccess<F>
        }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        const val SEARCH_RESPONSE =
            """{"results":{"result":[{"url":"https://example.com/a","title":"A","snippet":"B"}]}}"""
        const val CONTENTS_RESPONSE =
            """{"results":[{"url":"https://example.com/a","status":"success","content":"Page text"}]}"""
    }
}
