package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.CancellationException
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MacDocumentNativeRoundingTest {
    @Test
    fun `startup retries select one target and detach restores it without discovering Start views`() = onEdt {
        val access = Access()
        val rounding = MacDocumentNativeRounding(access)
        rounding.update(12.0)
        rounding.apply(JPanel())
        access.next = DocumentRoundCapture.Ambiguous
        rounding.apply(JPanel())
        assertEquals(0, access.target.rounds)
        access.next = DocumentRoundCapture.Found(access.target)
        rounding.apply(JPanel())
        rounding.apply(JPanel())
        assertEquals(3, access.captures)
        assertEquals(12.0, access.target.radius)
        rounding.update(0.0)
        rounding.apply(JPanel())
        rounding.close()
        assertEquals(3, access.captures)
        assertEquals(1, access.target.closes)
        assertEquals(3.0, access.target.radius)
    }

    @Test
    fun `reopening captures a fresh lease and old closure cannot retain or apply its target`() = onEdt {
        val access = Access()
        access.next = DocumentRoundCapture.Found(access.target)
        val rounding = MacDocumentNativeRounding(access)
        rounding.update(12.0)
        rounding.apply(JPanel())
        rounding.update(0.0)
        val replacement = Target()
        access.next = DocumentRoundCapture.Found(replacement)
        rounding.update(16.0)
        rounding.apply(JPanel())
        rounding.close()
        rounding.close()
        rounding.update(20.0)
        rounding.apply(JPanel())
        assertEquals(2, access.captures)
        assertEquals(1, access.target.closes)
        assertEquals(1, replacement.closes)
        assertEquals(3.0, replacement.radius)
    }

    @Test
    fun `failed application and failed restoration are contained and close the exact target only once`() = onEdt {
        val access = Access()
        access.target.applyError = IllegalStateException("apply")
        access.target.closeError = IllegalStateException("restore")
        access.next = DocumentRoundCapture.Found(access.target)
        val rounding = MacDocumentNativeRounding(access)
        rounding.update(12.0)
        rounding.apply(JPanel())
        rounding.apply(JPanel())
        rounding.close()
        assertEquals(1, access.captures)
        assertEquals(1, access.target.closes)
    }

    @Test
    fun `a cancelled operation propagates and retained cleanup remains available`() = onEdt {
        val access = Access()
        access.target.applyError = CancellationException("cancelled")
        access.next = DocumentRoundCapture.Found(access.target)
        val rounding = MacDocumentNativeRounding(access)
        rounding.update(12.0)
        assertFailsWith<CancellationException> { rounding.apply(JPanel()) }
        rounding.close()
        assertEquals(1, access.target.closes)
    }

    private class Access : DocumentRoundAccess {
        val target = Target()
        var next: DocumentRoundCapture = DocumentRoundCapture.Absent
        var captures = 0
        override fun capture(component: JComponent): DocumentRoundCapture {
            captures++
            return next
        }
    }

    private class Target : DocumentRoundTarget {
        var rounds = 0
        var closes = 0
        var radius = 3.0
        var applyError: Exception? = null
        var closeError: Exception? = null
        override fun round(radius: Double) {
            rounds++
            applyError?.let { throw it }
            this.radius = radius
        }
        override fun close() {
            closes++
            radius = 3.0
            closeError?.let { throw it }
        }
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
