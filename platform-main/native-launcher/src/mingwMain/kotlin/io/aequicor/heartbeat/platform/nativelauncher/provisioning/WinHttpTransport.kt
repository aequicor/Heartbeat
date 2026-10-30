package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp

/** Autonomous bootstrap has no core:network under Kotlin/Native; requests are bounded and streamed. */
@Suppress("LoggingInfrastructureBypass")
internal fun windowsHttpTransport(): HttpTransport = KtorBootstrapTransport(
    HttpClient(WinHttp) {
        expectSuccess = false
        followRedirects = true
    },
)
