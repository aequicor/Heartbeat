package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.researchchat.api.ResearchWorkspace
import kotlinx.collections.immutable.persistentMapOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ResearchPresentationTest {
    private val target = EngineTarget(EngineId("koog"), EngineBindingId("binding"), ModelId("model"))
    private val shared = ResearchResource("shared", "Shared", ResearchResourceKind.Document, "contents", "text/plain")
    private val local = shared.copy(id = "local", title = "Local")
    private val other = shared.copy(id = "other", title = "Other question only")
    private val question = ResearchQuestion("q", resourceIds = setOf("local"), excludedResourceIds = setOf("shared"))
    private val session = ResearchSession(
        "s",
        "Study",
        target,
        listOf(question, ResearchQuestion("q2", resourceIds = setOf("other"))),
        listOf(shared, local, other),
        setOf("shared"),
    )
    private val ready = ResearchChatState.Ready(target, ResearchWorkspace(listOf(session)), "s", "q")

    @Test
    fun `source panel excludes another questions private resources and preserves selection`() {
        val screen = ResearchScreenState().reflectResearch(ready)
        assertEquals(listOf("shared", "local"), screen.resources.map { it.id })
        assertEquals(listOf(false, true), screen.resources.map { it.isSelected })
        assertEquals(listOf(true, false), screen.resources.map { it.isShared })
    }

    @Test
    fun `question selection restores only its own draft`() {
        val screen = ResearchScreenState(drafts = persistentMapOf("q" to "first", "q2" to "second"))
        assertEquals("first", screen.reflectResearch(ready).draft)
        assertEquals("second", screen.reflectResearch(ready.copy(questionId = "q2")).draft)
    }

    @Test
    fun `resource payloads never become visible user prompt text`() {
        val item = SessionItem.Message(
            ItemInfo(ItemId("prompt"), 0, 0),
            MessageRole.User,
            listOf(
                ContentPart.Text("Question"),
                ContentPart.Resource(ResourceRef("data:text/plain;base64,c2VjcmV0", "text/plain")),
            ),
        )
        val state = ready.copy(
            workspace = ResearchWorkspace(
                listOf(session.copy(questions = listOf(question.copy(items = listOf(item))))),
            ),
        )
        assertEquals("Question", ResearchScreenState().reflectResearch(state).messages.single().text)
        assertFalse(ResearchScreenState().reflectResearch(ready.copy(isEnabled = false)).isEditable)
    }
}
