package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class DeepSeekAccountTest {
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @Test
    fun `missing credentials report needs login`() {
        runBlocking {
            val dir = Files.createTempDirectory("dsh-missing").toFile()
            val login = DeepSeekAccount(Clock.System, dispatchers).inspect(dir.path)
            assertEquals(AuthVerdict.NeedsLogin, login.check.verdict)
            assertEquals(AuthRevision.Unknown, login.check.revision)
        }
    }

    @Test
    fun `account token reports authenticated without claiming an account revision`() {
        runBlocking {
            val dir = Files.createTempDirectory("dsh-home").toFile()
            dir.resolve(".credentials.yaml").writeText(
                """
                version: 1
                records:
                  deepseek-account-platform/device:
                    payload:
                      id: device-123
                  deepseek-account-platform/default:
                    payload:
                      token: secret-token
                """.trimIndent(),
            )
            val login = DeepSeekAccount(Clock.System, dispatchers).inspect(dir.path)
            assertEquals(AuthVerdict.Authenticated, login.check.verdict)
            assertEquals(AuthRevision.Unknown, login.check.revision)
        }
    }

    @Test
    fun `different logins on the same device or without device never receive a known revision`() = runBlocking {
        val dir = Files.createTempDirectory("dsh-account-change").toFile()
        try {
            for (device in listOf("payload: {id: same-device}", "")) {
                for (token in listOf("first-login", "second-login")) {
                    dir.resolve(".credentials.yaml").writeText(
                        """
                        records:
                          deepseek-account-platform/device: {$device}
                          deepseek-account-platform/default: {payload: {token: $token}}
                        """.trimIndent(),
                    )
                    val login = DeepSeekAccount(Clock.System, dispatchers).inspect(dir.path)
                    assertEquals(AuthVerdict.Authenticated, login.check.verdict)
                    assertEquals(AuthRevision.Unknown, login.check.revision)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `record without token reports needs login`() {
        runBlocking {
            val dir = Files.createTempDirectory("dsh-empty").toFile()
            dir.resolve(".credentials.yaml").writeText(
                """
                version: 1
                records:
                  deepseek-account-platform/device:
                    payload:
                      id: device-123
                """.trimIndent(),
            )
            val login = DeepSeekAccount(Clock.System, dispatchers).inspect(dir.path)
            assertEquals(AuthVerdict.NeedsLogin, login.check.verdict)
        }
    }

    @Test
    fun `corrupt credentials report needs login without logging the content`() {
        runBlocking {
            val dir = Files.createTempDirectory("dsh-corrupt").toFile()
            dir.resolve(".credentials.yaml").writeText("records: [unclosed\n  token: secret-token")
            val login = DeepSeekAccount(Clock.System, dispatchers).inspect(dir.path)
            assertEquals(AuthVerdict.NeedsLogin, login.check.verdict)
        }
    }
}
