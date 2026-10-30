package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Explicit local integration check, skipped by normal JVM checks; no prompts or OAuth files are read. */
class KimiLocalApiLiveTest {
    @Test
    fun `installed Kimi reports native model capacities and account quotas`() = runTest(timeout = 60.seconds) {
        assumeTrue(java.lang.Boolean.getBoolean("heartbeat.kimi.localApiLiveTest"))
        val client = HttpClient(OkHttp)
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Default
            override val default = Dispatchers.Default
            override val io = Dispatchers.IO
        }
        try {
            val api = JvmKimiLocalApi(client, dispatchers, KimiConfiguration())
            val limits = api.modelLimits()
            assertTrue(limits.isNotEmpty(), "Native context capacity catalog is empty")
            assertTrue(limits.values.all { it > 0 })
            val quota = requireNotNull(api.usage()) { "Native quota is unavailable" }
            assertTrue(quota.windows.isNotEmpty(), "Account did not report quota windows")
            assertTrue(quota.windows.all { it.usedPercent != null })
        } finally {
            client.close()
        }
    }
}
