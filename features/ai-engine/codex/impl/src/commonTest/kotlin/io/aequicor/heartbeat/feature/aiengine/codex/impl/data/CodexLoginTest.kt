package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginCode
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodexLoginTest {
    private val wires = mutableListOf<FakeWire>()
    private var afterOpen: () -> Unit = {}
    private val prompts = mutableListOf<LoginPrompt>()
    private val session = object : LoginSession {
        override fun prompt(prompt: LoginPrompt) {
            prompts += prompt
        }

        override suspend fun awaitCode(): LoginCode = error("Codex never asks for a pasted code")
    }
    private var start = json(
        "loginId" to "L1".json(),
        "authUrl" to "https://auth.openai.com/oauth/authorize?s=1".json(),
    )
    private var account: JsonObject? = json(
        "type" to "chatgpt".json(),
        "email" to "me@example.com".json(),
    )

    private val wire: FakeWire get() = wires.last()

    @Test
    fun `cancellation immediately after opening the app-server releases its wire`() = runTest {
        lateinit var request: kotlinx.coroutines.Deferred<LoginState>
        afterOpen = { request.cancel() }
        request = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { logins().status(LaunchContext()) }
        request.start()
        runCurrent()
        assertTrue(request.isCancelled)
        assertTrue(wire.isClosed)
    }

    @Test
    fun `a browser sign-in shows OpenAI's page and returns the ChatGPT account`() = runTest {
        val login = async { logins().login(LaunchContext(), LoginMethod.Browser, session) }
        runCurrent()
        assertEquals(listOf<LoginPrompt>(LoginPrompt.OpenUrl("https://auth.openai.com/oauth/authorize?s=1")), prompts)
        assertEquals("chatgpt", requests("account/login/start").single().params().text("type"))

        complete(success = true)

        assertEquals(LoginState.SignedIn("me@example.com"), login.await())
        assertTrue(wire.isClosed)
    }

    @Test
    fun `a device sign-in shows the verification page and the user code`() = runTest {
        start = json(
            "loginId" to "L1".json(),
            "verificationUrl" to "https://auth.openai.com/codex/device".json(),
            "userCode" to "ABCD-EFGH".json(),
        )
        val login = async { logins().login(LaunchContext(), LoginMethod.DeviceCode, session) }
        runCurrent()

        val device = LoginPrompt.OpenUrl("https://auth.openai.com/codex/device", "ABCD-EFGH")
        assertEquals(listOf<LoginPrompt>(device), prompts)
        assertEquals("chatgptDeviceCode", requests("account/login/start").single().params().text("type"))
        complete(success = true)
        assertEquals(LoginState.SignedIn("me@example.com"), login.await())
    }

    @Test
    fun `a rejected sign-in or a page outside OpenAI fails`() = runTest {
        val rejected = async {
            assertFailsWith<ManagementException> { logins().login(LaunchContext(), LoginMethod.Browser, session) }
        }
        runCurrent()
        complete(success = false)
        assertEquals(ManagementFailure.Login(LoginFailureReason.Rejected), rejected.await().failure)

        start = json(
            "loginId" to "L2".json(),
            "authUrl" to "https://auth.openai.com.evil.example/x".json(),
        )
        prompts.clear()
        val untrusted = assertFailsWith<ManagementException> {
            logins().login(LaunchContext(), LoginMethod.Browser, session)
        }
        assertEquals(ManagementFailure.Login(LoginFailureReason.UntrustedUrl), untrusted.failure)
        assertEquals(emptyList<LoginPrompt>(), prompts)
    }

    @Test
    fun `a cancelled sign-in is cancelled on the app-server`() = runTest {
        val login = async { logins().login(LaunchContext(), LoginMethod.Browser, session) }
        runCurrent()

        login.cancel()
        runCurrent()

        assertEquals("L1", requests("account/login/cancel").single().params().text("loginId"))
        assertTrue(wire.isClosed)
    }

    @Test
    fun `status and sign-out read and clear the account`() = runTest {
        assertEquals(LoginState.SignedIn("me@example.com"), logins().status(LaunchContext()))
        account = json("type" to "apikey".json())
        assertEquals(LoginState.SignedOut, logins().status(LaunchContext()))
        account = null
        assertEquals(LoginState.SignedOut, logins().status(LaunchContext()))

        assertEquals(LoginState.SignedOut, logins().logout(LaunchContext()))
        assertEquals(1, requests("account/logout").size)
    }

    private fun TestScope.logins(): CodexLogin {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val transport = object : CodexTransport {
            override suspend fun open(): CodexWire = error("unused")
            override suspend fun open(launch: LaunchContext): CodexWire = appServer().also { afterOpen() }
            override suspend fun available(): EngineAvailability = EngineAvailability.Available
        }
        return CodexLogin(transport, dispatchers)
    }

    private suspend fun TestScope.complete(success: Boolean) {
        wire.event(
            "account/login/completed",
            json("loginId" to "L1".json(), "success" to JsonPrimitive(success), "error" to JsonNull),
        )
        runCurrent()
    }

    private fun appServer(): FakeWire = FakeWire().also { wire ->
        wires += wire
        wire.handler = { message ->
            when (message.text("method")) {
                "account/login/start" -> wire.reply(message, start)
                "account/read" -> wire.reply(message, json("account" to (account ?: JsonNull)))
                "initialized", null -> Unit
                else -> wire.reply(message, JsonObject(emptyMap()))
            }
        }
    }

    private fun requests(method: String) = wires.flatMap { it.written }.filter { it.text("method") == method }

    private fun JsonObject.params(): JsonObject = get("params") as JsonObject
}
