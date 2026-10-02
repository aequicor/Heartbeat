package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.hostOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * ChatGPT sign-in of the Codex CLI through a short-lived app-server of the given launch context, so the account
 * lands in the same `CODEX_HOME` the runtimes use. The app-server hosts the browser callback itself; Heartbeat only
 * shows the sign-in page (https on OpenAI's hosts) or the device code, and never sees tokens. Cancelling cancels
 * the pending sign-in on the app-server before it stops.
 */
@Inject
internal class CodexLogin(private val transport: CodexTransport, private val dispatchers: DispatcherProvider) {
    private val log = Log.tag("CodexLogin")

    /** The current account: signed in only with a ChatGPT login, which is the one Heartbeat can use. */
    suspend fun status(launch: LaunchContext): LoginState = connected(launch) { rpc -> account(rpc) }

    suspend fun login(launch: LaunchContext, method: LoginMethod, session: LoginSession): LoginState =
        connected(launch) { rpc ->
            val type = if (method == LoginMethod.DeviceCode) "chatgptDeviceCode" else "chatgpt"
            log.i { "Codex sign-in started method=$method" }
            val started = rpc.request("account/login/start", json("type" to type.json()))
            val loginId = started.text("loginId") ?: protocolFailure()
            val url = started.text("authUrl") ?: started.text("verificationUrl") ?: protocolFailure()
            if (!isOpenAiPage(url)) {
                log.w { "Codex offered a sign-in page outside OpenAI's hosts" }
                throw ManagementException(ManagementFailure.Login(LoginFailureReason.UntrustedUrl))
            }
            session.prompt(LoginPrompt.OpenUrl(url, started.text("userCode")))
            var isSettled = false
            val isSuccess = try {
                completion(rpc, loginId).also { isSettled = true }
            } finally {
                if (!isSettled) withContext(NonCancellable) { cancel(rpc, loginId) }
            }
            if (!isSuccess) {
                log.w { "Codex sign-in rejected" }
                throw ManagementException(ManagementFailure.Login(LoginFailureReason.Rejected))
            }
            log.i { "Codex sign-in completed" }
            account(rpc)
        }

    suspend fun logout(launch: LaunchContext): LoginState = connected(launch) { rpc ->
        rpc.request("account/logout")
        log.i { "Codex signed out" }
        LoginState.SignedOut
    }

    private suspend fun completion(rpc: CodexRpc, loginId: String): Boolean {
        val completed = rpc.notifications.first { message ->
            message.text("method") == "account/login/completed" &&
                (message["params"] as? JsonObject)?.text("loginId") == loginId
        }
        return ((completed["params"] as? JsonObject)?.get("success") as? JsonPrimitive)?.booleanOrNull == true
    }

    private suspend fun cancel(rpc: CodexRpc, loginId: String) {
        log.i { "Codex sign-in cancelled" }
        try {
            withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) {
                rpc.request("account/login/cancel", json("loginId" to loginId.json()))
            }
        } catch (e: EngineException) {
            log.w(e) { "Codex sign-in could not be cancelled; the app-server stops anyway" }
        }
    }

    private suspend fun account(rpc: CodexRpc): LoginState {
        val account = rpc.request(
            "account/read",
            json("refreshToken" to JsonPrimitive(false)),
        )["account"] as? JsonObject
        return when (account?.text("type")) {
            "chatgpt" -> LoginState.SignedIn(account.text("email"))
            else -> LoginState.SignedOut
        }
    }

    /** The reader has its own job, so a cancelled sign-in still receives the cancel acknowledgement. */
    private suspend fun <T> connected(launch: LaunchContext, block: suspend (CodexRpc) -> T): T =
        withContext(dispatchers.main) {
            val connection = Job()
            var opened: CodexWire? = null
            var owned: CodexRpc? = null
            try {
                // The transport can create a process before cancellation discards its dispatcher result.
                val wire = withContext(NonCancellable) { transport.open(launch).also { opened = it } }
                currentCoroutineContext().ensureActive()
                val rpc = CodexRpc(wire, CoroutineScope(coroutineContext + connection)).also { owned = it }
                rpc.initialize()
                block(rpc)
            } finally {
                if (owned != null) owned.close() else opened?.close()
                connection.cancel()
            }
        }

    private companion object {
        const val CANCEL_TIMEOUT_MILLIS = 5_000L
        val OpenAiHosts = listOf("openai.com", "chatgpt.com")

        fun isOpenAiPage(url: String): Boolean = url.startsWith("https://") &&
            hostOf(url).let { host -> OpenAiHosts.any { host == it || host.endsWith(".$it") } }
    }
}
