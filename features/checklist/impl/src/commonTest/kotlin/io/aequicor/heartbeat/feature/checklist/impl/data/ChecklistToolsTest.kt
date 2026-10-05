package io.aequicor.heartbeat.feature.checklist.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.checklist.api.ChecklistJournal
import io.aequicor.heartbeat.feature.checklist.api.ChecklistState
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecklistToolsTest {
    @Test
    fun `failed create retry confirms durable same card and never creates a duplicate`() = runTest {
        val stores = ChecklistTestStores()
        val storage = ChecklistStorage(stores)
        val machine = PersistingChecklist(
            storage,
            ChecklistJournal(
                generations = mapOf(EventKeys.sessionSegment(TEST_SESSION) to TEST_REQUEST),
            ),
        )
        val toggles = ChecklistTestToggles()
        val tools = ChecklistAgentTools(lazyOf(machine), lazyOf(storage), toggles)
        val context = AgentToolContext(
            TEST_SESSION,
            null,
            TurnId("facade"),
            TEST_REQUEST,
            historyTurn = TurnId("native"),
        )
        val args = Json.parseToJsonElement(
            """{"key":"manual","title":"Verify","mode":"MarkSessionReady",
            "fields":[{"id":"text","title":"Notes","type":"Text"}]}""",
        ).jsonObject
        stores.keyValue(KeyValueSpec("checklist_journal")).failWrites = true
        assertTrue(tools.execute(context, "checklist_create", args).isError)
        val id = (machine.state.value as ChecklistState.Ready).journal.cards.single().id
        stores.keyValue(KeyValueSpec("checklist_journal")).failWrites = false
        assertFalse(tools.execute(context, "checklist_create", args).isError)
        assertFalse(tools.execute(context, "checklist_create", args).isError)
        val saved = ChecklistStorage(stores).load().cards.single()
        assertEquals(id, saved.id)
        assertEquals(TurnId("native"), saved.historyTurn)
        assertEquals(listOf("checklist_create", "checklist_get"), tools.specifications(null).map { it.name })
        assertTrue(tools.execute(context, "checklist_create", JsonObject(emptyMap())).isError)
        toggles.enabled.value = false
        assertTrue(tools.specifications(null).isEmpty())
    }
}
