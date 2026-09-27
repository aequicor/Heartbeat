package io.aequicor.heartbeat.ds.resources

import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class HbResourcesTest {
    @Test
    fun `both languages resolve independently in the same process`() = runTest {
        assertEquals("Send", getString(HbString.Send.resource(HbLocale.English)))
        assertEquals("Отправить", getString(HbString.Send.resource(HbLocale.Russian)))
        assertEquals("Send", getString(HbString.Send.resource(HbLocale.English)))
    }

    @Test
    fun `every catalog resource has nonempty English and Russian copy`() = runTest {
        HbString.entries.forEach { key ->
            HbLocale.entries.forEach { locale ->
                assertTrue(getString(key.resource(locale)).isNotBlank(), "$key in $locale")
            }
        }
    }
}
