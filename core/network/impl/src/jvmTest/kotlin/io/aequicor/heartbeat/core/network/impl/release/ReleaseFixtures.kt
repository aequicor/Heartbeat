package io.aequicor.heartbeat.core.network.impl.release

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun releaseClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler)) {
    expectSuccess = true
    install(HttpTimeout)
}

internal fun dispatchers(io: CoroutineDispatcher = Dispatchers.IO): DispatcherProvider = object : DispatcherProvider {
    override val io = io
    override val main = io
    override val default = io
}

internal fun downloader(client: HttpClient, io: CoroutineDispatcher) = StreamingReleaseDownloader(
    client,
    dispatchers(io),
)

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)

internal inline fun assertReleaseFailure(failure: ReleaseFailure, block: () -> Unit) {
    assertEquals(failure, assertFailsWith<ReleaseException>(block = block).failure)
}

/** Runs the callback after the IO block, before its caller is resumed on the test scheduler. */
internal fun afterDispatch(
    delegate: kotlinx.coroutines.CoroutineDispatcher,
    after: () -> Unit,
): kotlinx.coroutines.CoroutineDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        delegate.dispatch(
            context,
            Runnable {
                block.run()
                after()
            },
        )
    }
}
