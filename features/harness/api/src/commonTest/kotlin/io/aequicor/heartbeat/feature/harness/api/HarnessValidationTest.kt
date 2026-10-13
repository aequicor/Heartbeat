package io.aequicor.heartbeat.feature.harness.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HarnessValidationTest {
    @Test
    fun `invalid item name reports only its public grammar and never retains the value`() {
        val error = assertFailsWith<HarnessValidationException> { ItemName("privateNameValue") }
        assertEquals(HarnessValidationCode.InvalidItemName, error.code)
        assertEquals(HarnessValidationCode.InvalidItemName.message, error.message)
        assertFalse("privateNameValue" in error.toString())
        assertEquals("save_record", ItemName("save_record").value)
    }
}
