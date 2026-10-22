package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioBrowserMentionTest {
    @Test
    fun `standalone mentions work at the start middle and on a new line`() {
        for (prompt in listOf("@browser", "Открой @browser страницу", "Открой\n@browser\tстраницу")) {
            assertNotNull(browserMentionDirective(prompt, isExternal = false))
            assertNotNull(browserMentionDirective(prompt, isExternal = true))
        }
    }

    @Test
    fun `emails file mentions longer names and slash arguments are not browser requests`() {
        for (prompt in listOf(
            "mail@browser", "@browser.kt", "@browser/file", "@browsers", "`@browser`",
            "https://example.com/@browser", "/remember use @browser", "/command @browser", "No mention",
        )) {
            assertNull(browserMentionDirective(prompt, isExternal = false), prompt)
        }
    }
}
