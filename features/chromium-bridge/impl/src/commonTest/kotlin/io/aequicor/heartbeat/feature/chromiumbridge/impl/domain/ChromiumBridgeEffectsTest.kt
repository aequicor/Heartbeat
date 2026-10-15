package io.aequicor.heartbeat.feature.chromiumbridge.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.chromiumbridge.api.BridgeServerStatus
import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEffect
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ConnectedBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExtensionPreparation
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistration
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistrationFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class ChromiumBridgeEffectsTest {
    private val server = FakeServer()
    private val hosts = FakeHosts()
    private val machine = RecordingScope()
    private val revealed = mutableListOf<String>()
    private val folder = object : ExtensionFolder {
        override suspend fun locate(): String = "/app/chromium-plugin"

        override suspend fun prepare(progress: suspend (ExtensionPreparation) -> Unit): String {
            progress(ExtensionPreparation.Downloading(20, 100))
            progress(ExtensionPreparation.Extracting)
            return "/cache/extension"
        }

        override suspend fun reveal(folder: String) {
            revealed += folder
        }
    }
    private val effects = ChromiumBridgeEffects(lazyOf(server), lazyOf(hosts), lazyOf(folder))

    @Test
    fun `serving reports status and connections until the socket is given up`() = runTest {
        val browser = ConnectedBrowser(1, "Brave", "140", "0.1.0", isAgentControlAllowed = true)
        server.clients.value = listOf(browser)

        effects.handle(ChromiumBridgeEffect.Serve, machine)

        assertEquals<List<ChromiumBridgeIntent>>(
            listOf(
                ChromiumBridgeIntent.Internal.ServerChanged(BridgeServerStatus.Starting),
                ChromiumBridgeIntent.Internal.ConnectionsChanged(listOf(browser)),
                ChromiumBridgeIntent.Internal.TabsChanged(2),
                ChromiumBridgeIntent.Internal.ServerChanged(BridgeServerStatus.Occupied),
            ),
            machine.sent,
        )
    }

    @Test
    fun `scanning and registration report their results`() = runTest {
        effects.handle(ChromiumBridgeEffect.ScanHosts, machine)
        effects.handle(ChromiumBridgeEffect.Register(ChromiumBrowser.Chrome), machine)
        effects.handle(ChromiumBridgeEffect.Unregister(ChromiumBrowser.Chrome), machine)

        assertEquals<List<ChromiumBridgeIntent>>(
            listOf(
                ChromiumBridgeIntent.Internal.HostsScanned(listOf(host(HostRegistration.NotRegistered))),
                ChromiumBridgeIntent.Internal.RegistrationFinished(host(HostRegistration.Current)),
                ChromiumBridgeIntent.Internal.RegistrationFinished(host(HostRegistration.NotRegistered)),
            ),
            machine.sent,
        )
    }

    @Test
    fun `a refused registration is reported with its reason`() = runTest {
        hosts.refusal = HostRegistrationFailure.Translocated

        effects.handle(ChromiumBridgeEffect.Register(ChromiumBrowser.Chrome), machine)

        assertEquals<List<ChromiumBridgeIntent>>(
            listOf(
                ChromiumBridgeIntent.Internal.RegistrationFailed(
                    ChromiumBrowser.Chrome,
                    HostRegistrationFailure.Translocated,
                ),
            ),
            machine.sent,
        )
    }

    @Test
    fun `the extension folder is located and revealed`() = runTest {
        effects.handle(ChromiumBridgeEffect.LocateExtension, machine)
        effects.handle(ChromiumBridgeEffect.RevealExtension("/app/chromium-plugin"), machine)

        assertEquals<List<ChromiumBridgeIntent>>(
            listOf(ChromiumBridgeIntent.Internal.ExtensionLocated("/app/chromium-plugin")),
            machine.sent,
        )
        assertEquals(listOf("/app/chromium-plugin"), revealed)
    }

    @Test
    fun `preparation reports progress before the final folder`() = runTest {
        effects.handle(ChromiumBridgeEffect.PrepareExtension, machine)
        assertEquals<List<ChromiumBridgeIntent>>(
            listOf(
                ChromiumBridgeIntent.Internal.ExtensionProgress(ExtensionPreparation.Downloading(20, 100)),
                ChromiumBridgeIntent.Internal.ExtensionProgress(ExtensionPreparation.Extracting),
                ChromiumBridgeIntent.Internal.ExtensionPrepared("/cache/extension"),
            ),
            machine.sent,
        )
        assertEquals(emptyList(), revealed)
    }

    @Test
    fun `revoking asks the server to release agent tabs`() = runTest {
        effects.handle(ChromiumBridgeEffect.RevokeTabs, machine)

        assertEquals(1, server.revocations)
    }

    private fun host(registration: HostRegistration) = BrowserHost(ChromiumBrowser.Chrome, true, registration)

    private class FakeServer : BridgeServer {
        val clients = MutableStateFlow<List<ConnectedBrowser>>(emptyList())
        var revocations = 0

        override suspend fun serve(onStatus: suspend (BridgeServerStatus) -> Unit) {
            onStatus(BridgeServerStatus.Starting)
            yield()
            onStatus(BridgeServerStatus.Occupied)
        }

        override fun clients(): Flow<List<ConnectedBrowser>> = clients

        override fun controlledTabs(): Flow<Int> = flowOf(2)

        override suspend fun revokeTabs(): Int = 1.also { revocations++ }
    }

    private inner class FakeHosts : NativeHostRegistry {
        var refusal: HostRegistrationFailure? = null

        override suspend fun scan(): List<BrowserHost> = listOf(host(HostRegistration.NotRegistered))

        override suspend fun register(browser: ChromiumBrowser): BrowserHost {
            refusal?.let { throw HostRegistrationException(it) }
            return host(HostRegistration.Current)
        }

        override suspend fun unregister(browser: ChromiumBrowser): BrowserHost = host(HostRegistration.NotRegistered)
    }

    private class RecordingScope : EffectScope<ChromiumBridgeIntent> {
        val sent = mutableListOf<ChromiumBridgeIntent>()

        override suspend fun send(intent: ChromiumBridgeIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }
}
