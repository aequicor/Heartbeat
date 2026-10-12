package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts

import io.aequicor.heartbeat.feature.chromiumbridge.api.BrowserHost
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBrowser
import io.aequicor.heartbeat.feature.chromiumbridge.api.HostRegistration
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionIdentity
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.HostRegistrationException
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.RegistrationProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopNativeHostRegistryTest {
    private val root = Files.createTempDirectory("hb-hosts")
    private val home = root.resolve("home")
    private val bridge = root.resolve("data/chromium-bridge")
    private val registry = FakeRegistry()
    private var socket = root.resolve("run/bridge.sock")
    private var launcher: HostLauncher = HostLauncher.Packaged(root.resolve("app/io.aequicor"))
    private var isWindows = false

    private val hosts = DesktopNativeHostRegistry(environment = { environment() }, registry, Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `browsers start unregistered and detection follows their user data`() = runTest {
        Files.createDirectories(home.resolve("Library/Application Support/BraveSoftware/Brave-Browser"))

        val scanned = hosts.scan()

        assertEquals(ChromiumBrowser.entries, scanned.map { it.browser })
        assertTrue(scanned.all { it.registration == HostRegistration.NotRegistered })
        assertEquals(listOf(ChromiumBrowser.Brave), scanned.filter { it.isDetected }.map { it.browser })
    }

    @Test
    fun `macOS registration writes the manifest and an executable wrapper`() = runTest {
        Files.createDirectories(chromeData())

        val host = hosts.register(ChromiumBrowser.Chrome)

        assertEquals(BrowserHost(ChromiumBrowser.Chrome, true, HostRegistration.Current), host)
        val manifest = HostManifest.decode(Files.readString(chromeManifest()))
        val wrapper = Path.of(manifest.path)
        assertEquals(ExtensionIdentity.origins, manifest.allowedOrigins)
        assertTrue(Files.isExecutable(wrapper))
        assertTrue(socket.toString() in Files.readString(wrapper))
    }

    @Test
    fun `a manifest of another build is reported and replaced only on request`() = runTest {
        Files.createDirectories(chromeManifest().parent)
        Files.writeString(chromeManifest(), HostManifest.encode(HostManifest.of("/elsewhere/host")))

        assertEquals(HostRegistration.OtherBuild, hosts.scan().first().registration)
        assertEquals(HostRegistration.Current, hosts.register(ChromiumBrowser.Chrome).registration)

        Files.writeString(chromeManifest(), "not json")
        assertEquals(HostRegistration.OtherBuild, hosts.scan().first().registration)
    }

    @Test
    fun `scanning refreshes this build's wrapper after the socket moved`() = runTest {
        hosts.register(ChromiumBrowser.Chrome)
        socket = root.resolve("moved/bridge.sock")

        val scanned = hosts.scan()

        assertEquals(HostRegistration.Current, scanned.first().registration)
        val wrapper = Path.of(HostManifest.decode(Files.readString(chromeManifest())).path)
        assertTrue(socket.toString() in Files.readString(wrapper))
    }

    @Test
    fun `development and installed builds keep separate wrappers`() = runTest {
        hosts.register(ChromiumBrowser.Chrome)
        launcher = HostLauncher.Development(root.resolve("jdk/bin/java"), "/a.jar")

        assertEquals(HostRegistration.OtherBuild, hosts.scan().first().registration)
        hosts.register(ChromiumBrowser.Chrome)

        val wrapper = Path.of(HostManifest.decode(Files.readString(chromeManifest())).path)
        assertTrue(wrapper.fileName.toString().endsWith("-dev"))
        assertTrue(Files.isRegularFile(wrapper.resolveSibling("heartbeat-chromium-host-dev.args")))
    }

    @Test
    fun `unregistering removes the manifest`() = runTest {
        hosts.register(ChromiumBrowser.Chrome)

        val host = hosts.unregister(ChromiumBrowser.Chrome)

        assertEquals(HostRegistration.NotRegistered, host.registration)
        assertFalse(Files.exists(chromeManifest()))
    }

    @Test
    fun `windows registration points the per user key at a manifest in the bridge directory`() = runTest {
        isWindows = true
        launcher = HostLauncher.Packaged(root.resolve("app/io.aequicor.exe"))

        val host = hosts.register(ChromiumBrowser.Edge)

        assertEquals(HostRegistration.Current, host.registration)
        val key = "Software\\Microsoft\\Edge\\NativeMessagingHosts\\io.aequicor.heartbeat"
        val manifest = Path.of(checkNotNull(registry.values[key]))
        assertTrue(manifest.startsWith(bridge))
        assertTrue(HostManifest.decode(Files.readString(manifest)).path.endsWith(".bat"))
        assertEquals(HostRegistration.NotRegistered, hosts.unregister(ChromiumBrowser.Edge).registration)
        assertNull(registry.values[key])
    }

    @Test
    fun `a translocated app cannot register`() = runTest {
        launcher = HostLauncher.Unavailable(RegistrationProblem.Translocated)

        val error = assertFailsWith<HostRegistrationException> { hosts.register(ChromiumBrowser.Chrome) }

        assertEquals(RegistrationProblem.Translocated, error.problem)
        assertFalse(Files.exists(chromeManifest()))
    }

    private fun environment() = HostEnvironment(
        locations = if (isWindows) BrowserLocations.windows(root.resolve("local")) else BrowserLocations.macOs(home),
        isWindows = isWindows,
        bridgeDirectory = bridge,
        socket = socket,
        launcher = launcher,
    )

    private fun chromeData(): Path = home.resolve("Library/Application Support/Google/Chrome")

    private fun chromeManifest(): Path = chromeData().resolve("NativeMessagingHosts/io.aequicor.heartbeat.json")

    private class FakeRegistry : WindowsRegistry {
        val values = mutableMapOf<String, String>()

        override fun defaultValue(key: String): String? = values[key]

        override fun setDefaultValue(key: String, value: String) {
            values[key] = value
        }

        override fun deleteKey(key: String) {
            values.remove(key)
        }
    }
}
