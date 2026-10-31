package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import dev.zacsweers.metro.Inject
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
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Clock

/**
 * Read-only detection of the DeepSeek Harness desktop login. It never reads token values (only their presence),
 * never logs them and never imports credentials. The stable revision is the device identity, not the token.
 */
@Inject
internal class DeepSeekAccount(private val clock: Clock) {
    private val log = Log.tag("DeepSeekAccount")

    suspend fun inspect(homeDirectory: String?): DeepSeekLogin {
        val file = credentialsFile(homeDirectory)
        val records = if (file.exists()) readRecords(file) else null
        val account = records?.record(ACCOUNT_RECORD)
        val device = records?.record(DEVICE_RECORD)
        val isLoggedIn = account?.payload()?.string("token")?.isNotBlank() == true
        val revision = if (isLoggedIn) {
            val identity = device?.payload()?.string("id") ?: "account"
            AuthRevision.Known(digest(identity))
        } else {
            AuthRevision.Unknown
        }
        log.i { "DeepSeek Harness login checked loggedIn=$isLoggedIn" }
        val source = AuthSource.CliLogin(
            AuthSourceInfo(DeepSeekEngine.AuthSource, "DeepSeek Harness", revision),
            AuthScope(ProviderId("deepseek"), EndpointOrigin(ORIGIN)),
            DeepSeekEngine.AuthOwner,
            DeepSeekEngine.AuthLocation,
        )
        return DeepSeekLogin(
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
        val root = try {
            Yaml().load<Any?>(file.readText()) as? Map<*, *>
        } catch (error: Exception) {
            log.w(error) { "DeepSeek Harness credentials could not be read" }
            return null
        } ?: return null
        val records = root["records"] as? Map<*, *> ?: root
        return records.entries.associate { (key, value) -> key.toString() to value }
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private companion object {
        const val ORIGIN = "https://api.deepseek.com"
        const val ACCOUNT_RECORD = "deepseek-account-platform/default"
        const val DEVICE_RECORD = "deepseek-account-platform/device"
    }
}

private fun Map<String, Any?>.record(name: String): Map<String, Any?>? =
    (this[name] as? Map<*, *>)?.entries?.associate { (key, value) -> key.toString() to value }

private fun Map<String, Any?>.payload(): Map<String, Any?>? = this.record("payload")

private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()?.takeIf(String::isNotBlank)
