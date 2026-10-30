package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.provisioning.HttpTransport
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.KtorBootstrapTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/** Autonomous bootstrap cannot depend on core:network, whose desktop implementation targets JVM. */
@Suppress("LoggingInfrastructureBypass")
internal actual fun createUnixTransport(): HttpTransport = KtorBootstrapTransport(HttpClient(Darwin))
