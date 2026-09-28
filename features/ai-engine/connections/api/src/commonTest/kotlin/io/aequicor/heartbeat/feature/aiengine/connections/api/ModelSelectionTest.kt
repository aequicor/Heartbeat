package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.connections.api.TestData.model
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelSelectionTest {
    private val a = model("a").target
    private val b = model("b").target
    private val other = model("c", EngineBindingId("binding-2")).target

    @Test
    fun `default model is always enabled`() {
        val selection = ModelSelection().withDefault(a)
        assertTrue(selection.isEnabled(a))
        assertEquals(a, selection.defaultTarget)
        assertFailsWith<IllegalArgumentException> { ModelSelection(defaultTarget = a) }
    }

    @Test
    fun `disabling the default model clears the default`() {
        val selection = ModelSelection().withModel(b, true).withDefault(a)
        assertNull(selection.withModel(a, false).defaultTarget)
        assertEquals(a, selection.withModel(b, false).defaultTarget)
        assertEquals(setOf(ModelId("a")), selection.withModel(b, false).enabled(a.binding))
    }

    @Test
    fun `forgetting a binding keeps other connections`() {
        val selection = ModelSelection().withModel(a, true).withDefault(other)
        val forgotten = selection.without(a.binding)
        assertFalse(forgotten.isEnabled(a))
        assertEquals(other, forgotten.defaultTarget)
        assertTrue(forgotten.bindings.none { it.binding == a.binding })
        assertNull(selection.without(other.binding).defaultTarget)
    }
}
