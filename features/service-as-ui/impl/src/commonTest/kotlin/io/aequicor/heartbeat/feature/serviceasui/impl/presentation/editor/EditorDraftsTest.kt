package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDraftsTest {
    private val saved = surface("map", UiPlacement.Panel, revision = 2).withSpec(LISTED)

    @Test
    fun `a clean editor shows the saved spec indented and its check previews it`() {
        val state = EditorState().synced(ready(saved), edited(saved))
        val check = checkDraft(state.text, edited(saved))

        assertEquals(EditorPhase.Ready, state.phase)
        assertTrue(state.text.lines().size > 1)
        assertEquals(2, state.base)
        assertTrue(check.isValid)
        assertFalse(check.isDirty)
        assertEquals(emptyList(), check.diagnostics)
        assertNotNull(check.spec)
        assertTrue(state.withCheck(check).isChecked)
    }

    @Test
    fun `a call is an error once the owner's methods are known and a warning before`() {
        val text = EditorState().synced(ready(saved), edited(saved)).text
        val unknown = checkDraft(text, edited(saved, methods = null))
        assertTrue(unknown.isValid)
        assertTrue(unknown.diagnostics.single().isWarning)

        val missing = checkDraft(text, edited(saved, methods = setOf("other")))
        assertFalse(missing.isValid)
        assertEquals("$.data.prs.method", missing.diagnostics.single().path)
    }

    @Test
    fun `layout changes are not edits and an invalid draft draws nothing`() {
        val clean = EditorState().synced(ready(saved), edited(saved))
        assertFalse(checkDraft(clean.text.replace(": ", ":"), edited(saved)).isDirty)

        val broken = checkDraft("{", edited(saved))
        assertTrue(broken.isDirty)
        assertFalse(broken.isValid)
        assertNull(broken.spec)
        assertEquals(clean, clean.withCheck(broken))
    }

    @Test
    fun `the size limit applies to the stored form not to the indented draft`() {
        val text = "x".repeat(ServiceUiLimits.SPEC_BYTES / 300 - 30)
        val children = (1..300).joinToString(",") { """{"type":"text","text":"$text"}""" }
        val big = saved.withSpec("""{"version":1,"body":{"type":"column","children":[$children]}}""")
        val state = EditorState().synced(ready(big), edited(big))

        assertTrue(big.content.spec.encodeToByteArray().size < ServiceUiLimits.SPEC_BYTES)
        assertTrue(state.text.encodeToByteArray().size > ServiceUiLimits.SPEC_BYTES)
        val check = checkDraft(state.text, edited(big))
        assertTrue(check.isValid, check.diagnostics.toString())
        assertFalse(check.isDirty)
    }

    @Test
    fun `a draft follows a switch of the surface and flags a changed spec until it returns`() {
        val draft = EditorState().synced(ready(saved), edited(saved)).typed(HELLO)
        assertTrue(draft.isDirty && !draft.isChecked)

        val switched = saved.copy(isEnabled = false, revision = 3)
        val rebased = draft.synced(ready(switched), edited(switched))
        assertEquals(HELLO, rebased.text)
        assertEquals(3, rebased.base)
        assertFalse(rebased.isChangedElsewhere)

        val changed = switched.withSpec(OTHER).copy(revision = 4)
        val conflicted = rebased.synced(ready(changed), edited(changed))
        assertEquals(HELLO, conflicted.text)
        assertEquals(3, conflicted.base)
        assertTrue(conflicted.isChangedElsewhere)

        val restored = changed.withSpec(LISTED).copy(revision = 5)
        val calm = conflicted.synced(ready(restored), edited(restored))
        assertEquals(5, calm.base)
        assertFalse(calm.isChangedElsewhere)

        val saved = changed.withSpec(HELLO).copy(revision = 6)
        val resolved = conflicted.synced(ready(saved), edited(saved))
        assertEquals(6, resolved.base)
        assertFalse(resolved.isChangedElsewhere)
        assertFalse(checkDraft(resolved.text, edited(saved)).isDirty)
    }

    @Test
    fun `an absent surface or library is a phase of the editor`() {
        assertEquals(EditorPhase.Missing, EditorState().synced(ready(), null).phase)
        assertEquals(EditorPhase.Disabled, EditorState().synced(ServiceUiState.Disabled(), null).phase)
        assertEquals(EditorPhase.Failed, EditorState().synced(ServiceUiState.Failed(attempt = 1), null).phase)
    }

    private fun ready(vararg surfaces: UiSurface) = ServiceUiState.Ready(library = UiLibrary(surfaces.toList()))

    private fun edited(surface: UiSurface, methods: Set<String>? = setOf("list_prs")) = editedSurface(
        ready(surface),
        surface,
        methods?.let { names -> UiMethods(isAvailable = true, names.map { UiMethodInfo(it, "") }) },
    )

    private fun UiSurface.withSpec(spec: String) = copy(content = content.copy(spec = spec))

    private companion object {
        const val LISTED = """{"version":1,"data":{"prs":{"method":"list_prs"}},"body":{"type":"text","text":"PRs"}}"""
        const val OTHER = """{"version":1,"body":{"type":"text","text":"Other"}}"""
        const val HELLO = """{"version":1,"body":{"type":"text","text":"Hello"}}"""
    }
}
