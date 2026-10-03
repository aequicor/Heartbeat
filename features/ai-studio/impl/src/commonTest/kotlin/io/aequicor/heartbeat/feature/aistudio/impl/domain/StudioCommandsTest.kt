package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioCommandsTest {
    @Test
    fun `remember command needs text after a separate command word`() {
        assertEquals("Use UTF-8 in scripts", rememberText("  /remember   Use UTF-8 in scripts "))
        assertEquals("line one\nline two", rememberText("/remember\nline one\nline two"))
        assertNull(rememberText("/remember"))
        assertNull(rememberText("/remember   "))
        assertNull(rememberText("/remembering things"))
        assertNull(rememberText("please /remember this"))
    }
}
