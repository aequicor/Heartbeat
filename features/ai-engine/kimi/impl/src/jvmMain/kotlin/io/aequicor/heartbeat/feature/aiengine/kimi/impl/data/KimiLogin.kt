package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/** A short-lived authentication connection owns a child scope, never the profile's scope. */
internal suspend fun withKimiAuthConnection(
    transport: AcpTransport,
    clients: AcpClientFactory,
    parent: CoroutineScope,
    action: suspend (AcpConnection) -> LoginState,
): LoginState {
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val handler = object : AcpClientHandler {
        override suspend fun onSessionUpdate(update: AcpSessionUpdate) = Unit
    }
    var connection: AcpConnection? = null
    try {
        connection = clients.connect(transport, scope, handler)
        connection.initialize(AcpImplementation("Heartbeat", "1"))
        return action(connection)
    } finally {
        withContext(NonCancellable) {
            try {
                if (connection == null) transport.close() else connection.close()
            } finally {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }
}

/** ACP checks readiness without reading Kimi's OAuth files or printing its credentials. */
internal suspend fun kimiLoginStatus(connection: AcpConnection): LoginState = try {
    val metadata = buildJsonObject { put("heartbeatOAuthStatus", true) }
    val response = connection.authenticate("login", metadata)
    val value = (response["_meta"] as? JsonObject)?.get("heartbeatOAuthSignedIn") as? JsonPrimitive
    val isSignedIn = value?.takeUnless { it.isString }?.booleanOrNull
    when (isSignedIn) {
        true -> LoginState.SignedIn()
        false -> LoginState.SignedOut
        null -> LoginState.Unknown
    }
} catch (error: AcpException.Remote) {
    Log.tag("KimiLogin").w(error.redactedFailure()) { "Kimi login status is unavailable" }
    LoginState.Unknown
}

/** Run the bundled CLI's device flow; output is bounded and never logged. */
internal suspend fun runKimiLogin(
    dispatchers: DispatcherProvider,
    session: LoginSession,
    timeoutMillis: Long,
    manualCommand: String? = null,
    openBrowser: (String) -> Unit = { url -> java.awt.Desktop.getDesktop().browse(URI(url)) },
    start: () -> Process,
): Boolean = withContext(dispatchers.io) {
    withTimeoutOrNull(timeoutMillis) {
        val process = start()
        coroutineScope {
            val guard = launch(dispatchers.io, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                }
            }
            try {
                process.outputStream.close()
                val isShown = readKimiLoginPrompt(
                    process.inputStream.bufferedReader(Charsets.UTF_8),
                    session,
                    manualCommand,
                    openBrowser,
                )
                isShown && runInterruptible { process.waitFor() } == 0
            } finally {
                guard.cancel()
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
                process.inputStream.close()
                process.errorStream.close()
            }
        }
    } ?: loginFailure(LoginFailureReason.TimedOut)
}

private suspend fun readKimiLoginPrompt(
    reader: java.io.Reader,
    session: LoginSession,
    manualCommand: String?,
    openBrowser: (String) -> Unit,
): Boolean {
    var url: String? = null
    var code: String? = null
    var isShown = false
    var read = 0
    while (true) {
        val line = runInterruptible { boundedLoginLine(reader) } ?: break
        read += line.length
        if (read > MAX_LOGIN_OUTPUT) loginFailure(LoginFailureReason.Rejected)
        if (line.startsWith(URL_PREFIX)) url = line.removePrefix(URL_PREFIX).trim()
        if (line.startsWith(CODE_PREFIX)) code = line.removePrefix(CODE_PREFIX).trim()
        if (!isShown) isShown = showKimiLoginPrompt(url, code, session, manualCommand, openBrowser)
    }
    return isShown
}

private fun showKimiLoginPrompt(
    url: String?,
    code: String?,
    session: LoginSession,
    manualCommand: String?,
    openBrowser: (String) -> Unit,
): Boolean {
    if (url == null || code == null) return false
    if (!isTrustedKimiUrl(url)) loginFailure(LoginFailureReason.UntrustedUrl)
    if (code.isBlank()) loginFailure(LoginFailureReason.Rejected)
    session.prompt(LoginPrompt.OpenUrl(url, code))
    try {
        openBrowser(url)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.tag("KimiLogin").w(error.redactedFailure()) { "Kimi login page could not open automatically" }
        throw ManagementException(ManagementFailure.Login(LoginFailureReason.RequiresTerminal, manualCommand))
    }
    return true
}

private fun boundedLoginLine(reader: java.io.Reader): String? {
    val line = StringBuilder()
    while (line.length <= MAX_LOGIN_LINE) {
        val char = reader.read()
        if (char < 0) return line.toString().takeIf { it.isNotEmpty() }
        if (char == '\n'.code) return line.toString().trimEnd('\r')
        line.append(char.toChar())
    }
    loginFailure(LoginFailureReason.Rejected)
}

internal fun isTrustedKimiUrl(value: String): Boolean = try {
    val uri = URI(value)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme.equals(
        "https",
        ignoreCase = true,
    ) && uri.userInfo == null && (uri.port == -1 || uri.port == HTTPS_PORT) &&
        (host == "kimi.com" || host.endsWith(".kimi.com") || host == "kimi.ai" || host.endsWith(".kimi.ai"))
} catch (error: java.net.URISyntaxException) {
    Log.tag("KimiLogin").w(error.redactedFailure()) { "Kimi login URL was malformed" }
    false
}

private fun loginFailure(reason: LoginFailureReason): Nothing =
    throw ManagementException(ManagementFailure.Login(reason))

private const val HTTPS_PORT = 443
private const val MAX_LOGIN_LINE = 16 * 1024
private const val MAX_LOGIN_OUTPUT = 64 * 1024
private const val URL_PREFIX = "Kimi device login URL: "
private const val CODE_PREFIX = "If the browser did not open, paste the URL above and enter code: "
