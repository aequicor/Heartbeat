package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import io.aequicor.heartbeat.feature.scheduler.api.WakeCondition
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import io.aequicor.heartbeat.feature.scheduler.api.WakeOrigin
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.SpawnRequest
import io.aequicor.heartbeat.feature.scheduler.api.spi.WakePrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudioScheduledSessionHostTest {
    private val session = SessionRef(EngineId("pi"), SessionSourceId("local"), "native-1")
    private val helper = SessionRef(EngineId("pi"), SessionSourceId("local"), "native-2")
    private val target = EngineTarget(EngineId("pi"), EngineBindingId("binding"), ModelId("model"))
    private val prompt = WakePrompt(RequestId("wake_w1"), "⏰ wake", "note for the agent")
    private val request = WakeRequest(
        WakeId("w1"),
        session,
        null,
        WakeCondition(deadline = Instant.parse("2026-10-05T10:00:00Z")),
        "note",
        WakeOrigin.Agent(TurnId("t1")),
    )

    private class Chats(val outcome: RunOutcome = RunOutcome.Completed, val isAccepted: Boolean = true) :
        StudioScheduledChats {
        val owned = mutableMapOf<SessionRef, StudioScheduledChat>()
        val sessions = mutableMapOf<String, SessionRef>()
        val runs = mutableListOf<List<Any?>>()
        val created = mutableListOf<Pair<String?, String>>()
        var helperWorkspace: WorkspaceRef? = null
        val finish = CompletableDeferred<Unit>()

        override suspend fun chatOf(session: SessionRef): StudioScheduledChat? = owned[session]
        override suspend fun sessionOf(chatId: String): SessionRef? = sessions[chatId]

        override suspend fun createHelperChat(projectId: String?, title: String, workspace: WorkspaceRef?): String {
            helperWorkspace = workspace
            created += projectId to title
            return "helper"
        }

        override suspend fun runScheduled(
            chatId: String,
            prompt: WakePrompt,
            route: ScheduledRunRoute,
            onAccepted: suspend () -> Unit,
        ): RunOutcome {
            runs += listOf(chatId, prompt, route)
            if (isAccepted) onAccepted()
            finish.await()
            return outcome
        }
    }

    private class Scope(override val coroutineScope: CoroutineScope) : ScopeHandle {
        override val name: String = "profile"
        override val savedState: ScopeSavedState get() = error("unused")
        override val isClosed: Boolean = false
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle { }
    }

    @Test
    fun `legacy spawn preserves both prompt and operation causal references`() = runTest {
        val chats = Chats().apply {
            owned[session] = StudioScheduledChat("parent", "project")
            sessions["helper"] = helper
        }
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        val initial = RequestInitiator(session, RequestId("initial"))
        val additional = RequestInitiator(session, RequestId("additional"))
        host.spawn(
            SpawnRequest(
                session,
                null,
                target,
                "Helper",
                prompt.copy(causes = setOf(initial)),
                setOf(additional),
            ),
        )
        assertEquals(setOf(initial, additional), (chats.runs.single()[1] as WakePrompt).causes)
        chats.finish.complete(Unit)
    }

    @Test
    fun `graph helper keeps the approved checkout separately from its parent project`() = runTest {
        val chats = Chats().apply { owned[session] = StudioScheduledChat("chat", "project") }
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        val workspace = WorkspaceRef("approved-worktree")
        assertEquals("helper", host.prepareTask(SpawnRequest(session, workspace, target, "Task", prompt)))
        assertEquals(listOf<Pair<String?, String>>("project" to "Task"), chats.created)
        assertEquals(workspace, chats.helperWorkspace)
        assertTrue(chats.runs.isEmpty())
    }

    @Test
    fun `a wake runs in the session's chat and returns on acceptance`() = runTest {
        val chats = Chats().apply { owned[session] = StudioScheduledChat("chat", "project") }
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        assertTrue(host.owns(session))
        assertFalse(host.owns(helper))
        host.wake(request, prompt)
        assertEquals(listOf<Any?>("chat", prompt, ScheduledRunRoute()), chats.runs.single())
    }

    @Test
    fun `a run that ends without acceptance fails the wake`() = runTest {
        val chats = Chats(RunOutcome.Failed, isAccepted = false).apply {
            owned[session] = StudioScheduledChat("chat", null)
            finish.complete(Unit)
        }
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        assertFailsWith<IllegalStateException> { host.wake(request, prompt) }
    }

    @Test
    fun `a helper gets its own chat in the parent's project on the given route`() = runTest {
        val chats = Chats().apply {
            owned[session] = StudioScheduledChat("chat", "project")
            sessions["helper"] = helper
        }
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        val spawned = host.spawn(SpawnRequest(session, WorkspaceRef("checkout"), target, "Helper", prompt))
        assertEquals(helper, spawned)
        assertEquals(listOf<Pair<String?, String>>("project" to "Helper"), chats.created)
        assertEquals(ScheduledRunRoute(target, approvalFrom = "chat"), chats.runs.single()[2])
    }

    @Test
    fun `a helper cannot start without an owning parent chat`() = runTest {
        val chats = Chats()
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        assertNull(host.spawn(SpawnRequest(session, WorkspaceRef("checkout"), target, "Helper", prompt)))
        assertTrue(chats.created.isEmpty())
        assertTrue(chats.runs.isEmpty())
    }

    @Test
    fun `a detached parent keeps its helper detached despite a workspace hint`() = runTest {
        val chats = Chats().apply {
            owned[session] = StudioScheduledChat("chat", null)
            sessions["helper"] = helper
        }
        val host = StudioScheduledSessionHost(
            lazyOf(chats),
            Scope(backgroundScope),
            lazy { error("unused helper storage") },
            lazy { error("unused helper orchestration") },
        )
        assertEquals(helper, host.spawn(SpawnRequest(session, WorkspaceRef("checkout"), target, "Helper", prompt)))
        assertEquals(listOf<Pair<String?, String>>(null to "Helper"), chats.created)
        assertEquals(ScheduledRunRoute(target, approvalFrom = "chat"), chats.runs.single()[2])
    }
}
