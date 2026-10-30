package io.aequicor.heartbeat.platform.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedHostConnectionTest {
    @Test
    fun `ordinary jpackage launch keeps the existing desktop window`() {
        assertNull(EmbeddedHostConnection.current())
    }

    @Test
    fun `train remains compatible with an installer without the optional host class`() {
        assertNull(EmbeddedHostConnection.current(object : ClassLoader(null) {}))
    }

    @Test
    fun `unsupported JAWT releases its cover before the ordinary desktop fallback`() {
        UnsupportedEmbeddedHostFixture.abandoned = false
        val fixture = UnsupportedEmbeddedHostFixture::class.java
        val loader = object : ClassLoader(null) {
            override fun getResource(name: String): java.net.URL = fixture.protectionDomain.codeSource.location

            override fun loadClass(name: String): Class<*> = fixture
        }
        val connection = assertNotNull(EmbeddedHostConnection.current(loader))
        assertNull(connection.createFrame())
        assertTrue(UnsupportedEmbeddedHostFixture.abandoned)
    }
}

/** Reflection fixture: optional embedding can decline before any application root is created. */
internal object UnsupportedEmbeddedHostFixture {
    var abandoned = false

    // The reflective selector ABI is a method, not a constant field.
    @Suppress("FunctionOnlyReturningConstant")
    @JvmStatic
    fun version(): Int = 1

    @JvmStatic
    fun createFrame(): java.awt.Frame = throw UnsupportedOperationException("JAWT API unavailable")

    @JvmStatic
    fun abandon() {
        abandoned = true
    }
}
