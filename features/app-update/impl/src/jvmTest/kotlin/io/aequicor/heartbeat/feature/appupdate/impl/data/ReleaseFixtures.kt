package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
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

/** A client configured like the app's: non-2xx responses throw, per-request timeouts are honoured. */
internal fun releaseClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler)) {
    expectSuccess = true
    install(HttpTimeout)
}

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)

/** Asserts that [block] fails with the update failure [failure]. */
internal inline fun assertUpdateFailure(failure: AppUpdateFailure, block: () -> Unit) {
    assertEquals(failure, assertFailsWith<AppUpdateException> { block() }.failure)
}

/** Real dispatchers: the tests drive files, processes and a mock HTTP engine. */
internal object TestDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
}
