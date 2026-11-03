package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheckBasis
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekEngine
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.api.DeepSeekLogin
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.time.Clock

/**
 * Read-only detection of the DeepSeek Harness desktop login. It observes token presence, never returns or logs
 * token material and never imports credentials. YAML parse errors are logged without the offending content:
 * parser messages can quote the credentials file. Revision stays Unknown: a device id does not identify the account.
 */
@Inject
internal class DeepSeekAccount(private val clock: Clock, private val dispatchers: DispatcherProvider) {
    private val log = Log.tag("DeepSeekAccount")

    suspend fun inspect(homeDirectory: String?): DeepSeekLogin = withContext(dispatchers.io) {
        val file = credentialsFile(homeDirectory)
        val records = if (file.exists()) readRecords(file) else null
        val account = records?.record(ACCOUNT_RECORD)
        val isLoggedIn = account?.payload()?.string("token")?.isNotBlank() == true
        val revision = AuthRevision.Unknown
        log.i { "DeepSeek Harness login checked loggedIn=$isLoggedIn" }
        val source = AuthSource.CliLogin(
            AuthSourceInfo(DeepSeekEngine.AuthSource, "DeepSeek Harness", revision),
            AuthScope(ProviderId("deepseek"), EndpointOrigin(ORIGIN)),
            DeepSeekEngine.AuthOwner,
            DeepSeekEngine.AuthLocation,
        )
        DeepSeekLogin(
            source,
            AuthCheck(
                source.info.id,
                DeepSeekEngine.AuthContext,
                if (isLoggedIn) AuthVerdict.Authenticated else AuthVerdict.NeedsLogin,
                AuthCheckBasis.Local,
                clock.now(),
                revision,
            ),
        )
    }

    private fun credentialsFile(homeDirectory: String?): File =
        File(homeDirectory ?: File(System.getProperty("user.home"), ".dsh").path, ".credentials.yaml")

    private fun readRecords(file: File): Map<String, Any?>? {
        if (file.length() > MAX_CREDENTIALS_BYTES) {
            log.w { "DeepSeek Harness credentials are too large to read" }
            return null
        }
        val root = try {
            Yaml().load<Any?>(file.readText()) as? Map<*, *>
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w { "DeepSeek Harness credentials could not be read: ${error.javaClass.name}" }
            return null
        } ?: return null
        val records = root["records"] as? Map<*, *> ?: root
        return records.entries.mapNotNull { (key, value) ->
            (key as? String)?.let { it to value }
        }.toMap()
    }

    private companion object {
        const val ORIGIN = "https://api.deepseek.com"
        const val ACCOUNT_RECORD = "deepseek-account-platform/default"
        const val MAX_CREDENTIALS_BYTES = 1024 * 1024L
    }
}

private fun Map<String, Any?>.record(name: String): Map<String, Any?>? =
    (this[name] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
        (key as? String)?.let { it to value }
    }?.toMap()

private fun Map<String, Any?>.payload(): Map<String, Any?>? = this.record("payload")

private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()?.takeIf(String::isNotBlank)
