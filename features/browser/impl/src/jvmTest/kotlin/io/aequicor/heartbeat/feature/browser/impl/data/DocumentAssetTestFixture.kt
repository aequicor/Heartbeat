package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestDispatchers
import io.aequicor.heartbeat.feature.browser.impl.presentation.BrowserTestScope
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

internal fun TestScope.documentAssets(
    configure: HttpClientConfig<*>.() -> Unit = {},
    handler: MockRequestHandler,
): DocumentAssetTestFixture {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val engine = MockEngine(
        MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler(handler)
        },
    )
    val client = HttpClient(engine) {
        expectSuccess = true
        install(HttpRequestRetry) { retryOnExceptionOrServerErrors(3) }
        configure()
    }
    val profile = BrowserTestScope(backgroundScope)
    return DocumentAssetTestFixture(
        engine,
        client,
        profile,
        HttpBrowserDocumentAssets(client, BrowserTestDispatchers(dispatcher), profile),
    )
}

internal class DocumentAssetTestFixture(
    val engine: MockEngine,
    val client: HttpClient,
    val profile: BrowserTestScope,
    val assets: HttpBrowserDocumentAssets,
) : AutoCloseable {
    override fun close() {
        profile.close()
        client.close()
        engine.close()
    }
}

internal const val ASSET_URL = "https://assets.example/library/main.css"
