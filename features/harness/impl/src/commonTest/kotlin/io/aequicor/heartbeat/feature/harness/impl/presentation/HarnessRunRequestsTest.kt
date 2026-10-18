package io.aequicor.heartbeat.feature.harness.impl.presentation

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionChoice
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionInput
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.accepts
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessRunRequestsTest {
    private val run = RunId("wf_one")

    @Test
    fun `a plain request maps to buttons that answer without input`() {
        val ui = permission(null).toUi(run)
        assertEquals(RequestKindUi.Buttons, ui.kind)
        assertEquals(listOf("allow", "deny", "skip"), ui.options.map { it.id })
        assertEquals("s0/c1/turn/req", ui.key)
        val allow = PermissionDecision(TurnId("turn"), PermissionRequestId("req"), PermissionOptionId("allow"))
        assertEquals(allow, ui.decision("allow", setOf("ignored"), "ignored"))
        assertNull(ui.decision("unoffered", emptySet(), ""))
    }

    @Test
    fun `a single choice needs exactly one value and a skip needs none`() {
        val ui = permission(PermissionInput.SingleChoice(CHOICES)).toUi(run)
        assertEquals(RequestKindUi.SingleChoice, ui.kind)
        assertEquals(1 to 1, ui.min to ui.max)
        assertNull(ui.decision("allow", emptySet(), ""))
        assertNull(ui.decision("allow", setOf("a", "b"), ""))
        assertEquals(PermissionAnswer.Selected(listOf("b")), ui.decision("allow", setOf("b"), "")?.answer)
        val skipped = ui.decision("skip", emptySet(), "")
        assertEquals(PermissionOptionId("skip"), skipped?.option)
        assertNull(skipped?.answer)
        assertEquals(setOf("b"), ui.changeSelection(setOf("a"), "b", isChecked = true))
    }

    @Test
    fun `a multiple choice keeps its bounds and the order the request offers`() {
        val ui = permission(PermissionInput.MultiChoice(CHOICES, min = 1, max = 2)).toUi(run)
        assertEquals(RequestKindUi.MultiChoice, ui.kind)
        assertNull(ui.decision("allow", emptySet(), ""))
        assertNull(ui.decision("allow", setOf("a", "b", "c"), ""))
        assertEquals(PermissionAnswer.Selected(listOf("a", "b")), ui.decision("allow", setOf("b", "a"), "")?.answer)
        assertEquals(setOf("a", "b"), ui.changeSelection(setOf("a"), "b", isChecked = true))
        assertEquals(setOf("a"), ui.changeSelection(setOf("a", "b"), "b", isChecked = false))
    }

    @Test
    fun `a free text answer travels with every option but a skip`() {
        val ui = permission(PermissionInput.FreeText("Why?", isMultiline = true)).toUi(run)
        assertEquals(RequestKindUi.FreeText, ui.kind)
        assertEquals("Why?", ui.placeholder)
        assertTrue(ui.isMultiline)
        assertEquals(PermissionAnswer.Text("because"), ui.decision("allow", emptySet(), "because")?.answer)
        assertNull(ui.decision("skip", emptySet(), "because")?.answer)
    }

    @Test
    fun `every decision the card builds is one the engine would accept`() {
        val inputs = listOf(
            null,
            PermissionInput.SingleChoice(CHOICES),
            PermissionInput.MultiChoice(CHOICES, 1, 2),
            PermissionInput.FreeText(),
        )
        for (input in inputs) {
            val asking = permission(input)
            val ui = asking.toUi(run)
            for (option in ui.options) {
                val decision = ui.decision(option.id, setOf("a"), "text") ?: continue
                assertTrue(asking.request.accepts(decision), "${input ?: "plain"} ${option.id}")
            }
        }
    }

    @Test
    fun `request texts are not rendered`() {
        val ui = permission(null, "private text").toUi(run)
        assertFalse("private" in ui.toString())
        val intent = HarnessDetailIntent.DecideRequest(ui, "allow", setOf("private"), "private")
        assertFalse("private" in intent.toString())
    }

    private fun permission(input: PermissionInput?, title: String = "Run a command?") = OrganismPermission(
        StepKey("s0"),
        "c1",
        "Builder",
        PermissionRequest(
            PermissionRequestId("req"),
            TurnId("turn"),
            title,
            listOf(
                PermissionOption(PermissionOptionId("allow"), "Allow"),
                PermissionOption(PermissionOptionId("deny"), "Deny"),
                PermissionOption(PermissionOptionId("skip"), "Skip", isSkip = input != null),
            ),
            description = title,
            input = input,
        ),
    )

    private companion object {
        val CHOICES = listOf(PermissionChoice("a", "A"), PermissionChoice("b", "B"), PermissionChoice("c", "C"))
    }
}
