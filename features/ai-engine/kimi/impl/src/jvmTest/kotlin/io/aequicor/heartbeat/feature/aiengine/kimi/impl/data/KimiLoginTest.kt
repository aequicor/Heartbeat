package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPromptResult
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KimiLoginTest {
    @Test
    fun `OAuth status is independent of model readiness and unknown responses fail closed`() = runTest {
        assertEquals(LoginState.SignedIn(), kimiLoginStatus(StatusConnection(signedIn = true)))
        assertEquals(LoginState.SignedOut, kimiLoginStatus(StatusConnection(signedIn = false)))
        assertEquals(LoginState.Unknown, kimiLoginStatus(StatusConnection()))
        assertEquals(LoginState.Unknown, kimiLoginStatus(StatusConnection(malformed = true)))
        assertEquals(LoginState.Unknown, kimiLoginStatus(StatusConnection(errorCode = -32000)))
    }

    @Test
    fun `login displays the bundled CLI URL and device code and releases its process`() = runTest {
        val session = CapturingLoginSession()
        val process = LoginProcess(
            "Kimi device login URL: https://auth.kimi.com/device\n" +
                "If the browser did not open, paste the URL above and enter code: ABCD-1234\n",
        )
        var opened: String? = null
        assertTrue(runKimiLogin(dispatchers, session, 5_000, openBrowser = { opened = it }) { process })
        assertEquals("https://auth.kimi.com/device", opened)
        assertEquals(LoginPrompt.OpenUrl("https://auth.kimi.com/device", "ABCD-1234"), session.prompt)
        assertTrue(process.destroyed.get())
    }

    @Test
    fun `browser failure reports terminal fallback without waiting`() = runTest {
        val session = CapturingLoginSession()
        val process = LoginProcess(
            "Kimi device login URL: https://auth.kimi.com/device\n" +
                "If the browser did not open, paste the URL above and enter code: ABCD\n",
        )
        val failure = assertFailsWith<ManagementException> {
            runKimiLogin(dispatchers, session, 5_000, "kimi login", { throw IllegalStateException("unavailable") }) {
                process
            }
        }
        val login = assertIs<ManagementFailure.Login>(failure.failure)
        assertEquals(LoginFailureReason.RequiresTerminal, login.reason)
        assertEquals("kimi login", login.terminalCommand)
        assertTrue(process.destroyed.get())
    }

    @Test
    fun `login rejects untrusted URLs and releases its process`() = runTest {
        val session = CapturingLoginSession()
        val process = LoginProcess(
            "Kimi device login URL: https://kimi.com.evil.example/device\n" +
                "If the browser did not open, paste the URL above and enter code: ABCD\n",
        )
        assertFailsWith<ManagementException> {
            runKimiLogin(
                dispatchers,
                session,
                5_000,
                openBrowser = { error("Untrusted URL must not open") },
            ) { process }
        }
        assertEquals(null, session.prompt)
        assertTrue(process.destroyed.get())
    }

    @Test
    fun `login does not claim success without a device prompt`() = runTest {
        val process = LoginProcess("nothing useful\n")
        assertFalse(runKimiLogin(dispatchers, CapturingLoginSession(), 5_000) { process })
        assertTrue(process.destroyed.get())
    }

    @Test
    fun `cancellation kills a quiet login process without waiting for its pipe`() = runBlocking {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 30 127.0.0.1 > nul")
        } else {
            listOf("/bin/sh", "-c", "sleep 30")
        }
        val started = CompletableDeferred<Process>()
        val login = async {
            runKimiLogin(dispatchers, CapturingLoginSession(), 60_000) {
                ProcessBuilder(command).redirectErrorStream(true).start().also { started.complete(it) }
            }
        }
        val process = started.await()
        withTimeout(5_000) { login.cancelAndJoin() }
        assertFalse(process.isAlive)
    }

    @Test
    fun `timeout kills a quiet login process and reports timeout`() = runBlocking {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/c", "ping -n 30 127.0.0.1 > nul")
        } else {
            listOf("/bin/sh", "-c", "sleep 30")
        }
        lateinit var process: Process
        val failure = assertFailsWith<ManagementException> {
            runKimiLogin(dispatchers, CapturingLoginSession(), 300) {
                ProcessBuilder(command).redirectErrorStream(true).start().also { process = it }
            }
        }
        assertEquals(LoginFailureReason.TimedOut, assertIs<ManagementFailure.Login>(failure.failure).reason)
        assertFalse(process.isAlive)
    }

    @Test
    fun `terminal fallback quotes the executable for each shell`() {
        assertEquals("'a'\\''b' login", kimiTerminalLoginCommand("a'b", isWindows = false))
        assertEquals("& 'a''b' login", kimiTerminalLoginCommand("a'b", isWindows = true))
    }

    @Test
    fun `login URL must be HTTPS without user information or custom port`() {
        assertTrue(isTrustedKimiUrl("https://auth.kimi.ai/device"))
        assertFalse(isTrustedKimiUrl("http://auth.kimi.com/device"))
        assertFalse(isTrustedKimiUrl("https://user@auth.kimi.com/device"))
        assertFalse(isTrustedKimiUrl("https://auth.kimi.com:8443/device"))
        assertFalse(isTrustedKimiUrl("https://auth.kimi.com.evil.example/device"))
        assertFalse(isTrustedKimiUrl("not a URI"))
    }

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Default
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }
}

private class CapturingLoginSession : LoginSession {
    var prompt: LoginPrompt? = null
    override fun prompt(prompt: LoginPrompt) {
        this.prompt = prompt
    }
    override suspend fun awaitCode(): LoginCode = error("Device flow does not ask for a pasted code")
}

private class LoginProcess(output: String, private val result: Int = 0) : Process() {
    val destroyed = AtomicBoolean()
    private val input = ByteArrayInputStream(output.toByteArray())
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun getInputStream() = input
    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
    override fun waitFor() = result
    override fun exitValue() = result
    override fun destroy() {
        destroyed.set(true)
    }
    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()
}

private class StatusConnection(
    private val signedIn: Boolean? = null,
    private val errorCode: Int? = null,
    private val malformed: Boolean = false,
) : AcpConnection {
    override suspend fun authenticate(methodId: String) {
        assertEquals("login", methodId)
        errorCode?.let { throw AcpException.Remote(it, "private") }
    }
    override suspend fun authenticate(methodId: String, metadata: JsonObject): JsonObject {
        assertEquals("login", methodId)
        assertEquals(buildJsonObject { put("heartbeatOAuthStatus", true) }, metadata)
        errorCode?.let { throw AcpException.Remote(it, "private") }
        return buildJsonObject {
            if (signedIn != null) put("_meta", buildJsonObject { put("heartbeatOAuthSignedIn", signedIn) })
            if (malformed) put("_meta", buildJsonObject { put("heartbeatOAuthSignedIn", "true") })
        }
    }
    override suspend fun initialize(client: AcpImplementation) = AcpInitialization(1)
    override suspend fun newSession(cwd: String) = AcpSession("session", JsonObject(emptyMap()))
    override suspend fun loadSession(sessionId: String, cwd: String) = AcpSession(sessionId, JsonObject(emptyMap()))
    override suspend fun prompt(sessionId: String, content: List<JsonObject>) = AcpPromptResult("end_turn")
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun close() = Unit
}
