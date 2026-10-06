package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHookContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.event.EngineEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessBusEvent
import io.aequicor.heartbeat.feature.harness.api.event.HarnessLifecycleEvent
import io.aequicor.heartbeat.feature.harness.api.event.SchedulerEvent
import io.aequicor.heartbeat.feature.harness.api.event.SessionEvent
import io.aequicor.heartbeat.feature.harness.api.event.SystemEvent
import io.aequicor.heartbeat.feature.harness.api.event.UntrustedEventPayload
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStatus
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKeys
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.WakeFailure
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeReason
import io.aequicor.heartbeat.feature.scheduler.api.WakeRejection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

class HarnessScriptPrivacyTest {
    private val privateText = "private-sensitive-sentinel"
    private val engine = EngineId(privateText)
    private val session = SessionRef(engine, SessionSourceId(privateText), privateText)
    private val turn = TurnId(privateText)
    private val target = EngineTarget(engine, EngineBindingId(privateText), ModelId(privateText))
    private val at = Instant.fromEpochSeconds(0)
    private val context = SessionHookContext(
        session,
        WorkspaceRef(privateText),
        RequestId(privateText),
        turn,
        SessionOwner(privateText),
    )

    @Test
    fun `script results and contexts never render their nested private content`() {
        val page = HistoryPage(
            listOf(
                SessionItem.Message(
                    ItemInfo(io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId("message"), 0, 0),
                    MessageRole.Assistant,
                    listOf(ContentPart.Text(privateText)),
                ),
            ),
            null,
            null,
            HistoryCheckpoint(privateText),
            HistoryCoverage.Complete,
        )
        val values = listOf(
            ScriptSession(session, privateText, WorkspaceRef(privateText), target),
            ScriptHistory(session, page),
            ScriptHelper(HelperId(privateText), RequestId(privateText), session),
            ScriptToolCall(
                AgentToolContext(session, null, turn),
                JsonObject(mapOf("text" to JsonPrimitive(privateText))),
            ),
            ScriptToolResult(privateText),
            UntrustedEventPayload(privateText),
        )
        values.forEach { assertFalse(privateText in it.toString(), it::class.simpleName) }
    }

    @Test
    fun `all event groups redact nested content including notes permission titles and workflow results`() {
        val key = EventKeys.custom(privateText)
        val id = WakeId("wake")
        val permission = PermissionRequest(
            PermissionRequestId(privateText),
            turn,
            privateText,
            listOf(PermissionOption(PermissionOptionId("allow"), privateText)),
        )
        val events = listOf(
            SystemEvent.Started(at),
            SystemEvent.NetworkChanged(true, at),
            HarnessBusEvent(key, EventOrigin.Session(session), at, UntrustedEventPayload(privateText)),
            EngineEvent.AvailabilityChanged(engine, EngineAvailability.Available, at),
            EngineEvent.ConnectionsChanged(engine, setOf(target.binding), at),
            SchedulerEvent.WakeScheduled(id, session, at),
            SchedulerEvent.Woke(id, session, WakeReason.Event(BusEvent(key, EventOrigin.Host, at, privateText)), at),
            SchedulerEvent.WakeFailed(listOf(id), WakeFailure.Engine, at),
            SchedulerEvent.WakesCancelled(listOf(id), at),
            SchedulerEvent.WakeRejected(id, WakeRejection.Duplicate, at),
            SessionEvent.Opened(context, at),
            SessionEvent.TurnStarted(context, at),
            SessionEvent.TurnFinished(context, TurnOutcome.Completed, at),
            SessionEvent.PermissionRequested(context, permission, at),
            SessionEvent.Closed(context, at),
            HarnessLifecycleEvent.Activated(HarnessId(privateText), ItemId(privateText), 1, at),
            HarnessLifecycleEvent.WorkflowFinished(
                HarnessId(privateText),
                ItemId(privateText),
                RunId("wf_private"),
                WorkflowStatus.Completed(JsonPrimitive(privateText)),
                at,
            ),
        )
        events.forEach { assertFalse(privateText in it.toString(), it::class.simpleName) }
    }

    @Test
    fun `workflow finished event cannot claim a running workflow is terminal`() {
        assertFailsWith<IllegalArgumentException> {
            HarnessLifecycleEvent.WorkflowFinished(
                HarnessId("harness"),
                ItemId("workflow"),
                RunId("wf_running"),
                WorkflowStatus.Running,
                at,
            )
        }
    }
}
