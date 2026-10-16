package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBrowserInterstitialTest {
    @Test
    fun `ordinary pages and a different failed URL cannot expose certificate actions`() = runTest {
        val fake = Transport()
        val probe = DesktopBrowserInterstitial(fake::call)
        fake.documentUrl = "https://example.com/"
        assertFalse(probe.isProceedAllowed(fake.url))
        fake.documentUrl = "chrome-error://chromewebdata/"
        assertFalse(probe.proceed("https://other.example/"))
        assertEquals(0, fake.evaluations)
    }

    @Test
    fun `HSTS without native proceed link stays blocking`() = runTest {
        val fake = Transport()
        fake.hasLink = false
        assertFalse(DesktopBrowserInterstitial(fake::call).isProceedAllowed(fake.url))
        assertFalse(DesktopBrowserInterstitial(fake::call).proceed(fake.url))
    }

    @Test
    fun `replaced native document and revoked authorization do not execute a click`() = runTest {
        val fake = Transport()
        val probe = DesktopBrowserInterstitial(fake::call)
        assertTrue(probe.isProceedAllowed(fake.url, "failure-1"))
        fake.evaluations = 0
        fake.changesLoader = true
        assertFalse(probe.proceed(fake.url, "failure-1"))
        assertEquals(0, fake.evaluations)
        fake.changesLoader = false
        assertFalse(probe.proceed(fake.url, "failure-1") { false })
        assertFalse(probe.proceed(fake.url, "failure-2"))
        assertEquals(0, fake.evaluations)
    }

    @Test
    fun `native link is evaluated in the pinned isolated context`() = runTest {
        val fake = Transport()
        val probe = DesktopBrowserInterstitial(fake::call)
        assertTrue(probe.isProceedAllowed(fake.url))
        assertTrue(probe.proceed(fake.url))
        assertEquals(77, fake.context)
        assertTrue(fake.script.contains("link.click()"))
    }

    private class Transport {
        val url = "https://example.com/"
        var documentUrl = "chrome-error://chromewebdata/"
        var hasLink = true
        var changesLoader = false
        var evaluations = 0
        var frames = 0
        var context: Int? = null
        var script = ""

        suspend fun call(method: String, args: JsonObject): String = when (method) {
            "Page.getFrameTree" -> {
                frames++
                """{"frameTree":{"frame":{"id":"main","loaderId":"${if (changesLoader) frames else 1}","url":"$documentUrl","unreachableUrl":"$url"}}}"""
            }

            "Page.createIsolatedWorld" -> """{"executionContextId":77}"""

            "Runtime.evaluate" -> {
                evaluations++
                context = args["contextId"].toString().toInt()
                script = args["expression"].toString()
                """{"result":{"value":$hasLink}}"""
            }

            else -> error("Unexpected CDP method: $method")
        }
    }
}
