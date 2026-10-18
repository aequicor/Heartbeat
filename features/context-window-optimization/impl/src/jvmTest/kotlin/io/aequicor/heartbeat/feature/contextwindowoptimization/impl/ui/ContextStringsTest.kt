package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class ContextStringsTest {
    /** Compose resources substitute only numbered arguments, so an escaped percent is shown literally as "%%". */
    @Test
    fun `percent signs next to arguments are not escaped`() {
        listOf("values", "values-ru").forEach { directory ->
            val text = File("src/commonMain/composeResources/$directory/strings.xml").readText()
            assertFalse("%%" in text, "$directory/strings.xml contains an escaped percent")
        }
    }
}
