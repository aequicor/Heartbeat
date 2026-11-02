package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueAction
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class StudioQueueRoleAccessTest {
    @Test
    fun `queue delivery revalidates role before opening or capturing human authority`() = runTest {
        val root = StudioChatRecord("root", "Root", Instant.fromEpochSeconds(0))
        val executor = StudioChatRecord(
            "agent",
            "Agent",
            Instant.fromEpochSeconds(0),
            identity = StudioSessionIdentity(
                StudioIdentityOrigin.AgentCreated,
                LogicalSessionContext(
                    LogicalSessionId("root"),
                    SessionKind.Session,
                ).subagent(LogicalSessionId("agent")),
            ),
        )
        val records = records()
        records.store.set(ChatsKey, listOf(root, executor))
        val access = StudioHumanQueue(records, NoStudioQueue, lazy { error("Do not look up an executor") })
        val message = StudioQueuedMessage("message", "New instruction", emptyList(), DefaultRunSettings)
        assertFailsWith<EngineException> { access.enqueue("agent", message, null, false) }
        assertFailsWith<EngineException> { access.manage("agent", StudioQueueAction.Resume, "") }
        assertFailsWith<EngineException> { access.steeringTarget("agent") }
        val fixture = StudioConfigurationFixture(this)
        var originals = 0
        val authority = object : StudioConfigurationAccess by fixture.access {
            override suspend fun captureUserInstructions(
                id: String,
                request: RequestId,
                instruction: String?,
            ): TrustedUserInstructions {
                originals++
                return TrustedUserInstructions.Incomplete
            }
        }
        val delivery = StudioQueueDelivery(
            lazyOf(access),
            lazy { error("Do not open an executor for human input") },
            lazyOf(authority),
            StudioRunCoordinator(RunProfile(backgroundScope), RunClock),
            StudioWorktrees(ConfigurationRegistry(), NoAgentTools),
        )
        assertFailsWith<EngineException> {
            delivery.queuedRun("agent", message, null, StudioRunSubmission()) { error("Do not acknowledge") }
        }
        val session = ConfigurationSession(fixture.target, "agent", null)
        assertFailsWith<EngineException> {
            delivery.steerQueued(StudioSteeringTarget(session, TurnId("captured"), "agent"), message)
        }
        assertEquals(0, originals)
    }

    @Test
    fun `an old interactive subsession can enqueue and resume independently of its helper marker`() = runTest {
        val records = records()
        val at = Instant.fromEpochSeconds(0)
        records.store.set(
            ChatsKey,
            listOf(
                StudioChatRecord("root", "Root", at),
                StudioChatRecord(
                    "child",
                    "Child",
                    at,
                    helper = StudioHelperIdentity(ActionId("old-work"), "root", null, TrustLevel.Ask),
                ),
            ),
        )
        var enqueued = 0
        var resumed = 0
        val queue = object : StudioQueuePort {
            override suspend fun enqueue(
                chat: String,
                message: StudioQueuedMessage,
                environment: StudioChatEnvironment?,
                isCorrection: Boolean,
            ) {
                assertEquals("child", chat)
                enqueued++
            }
            override suspend fun resume(chat: String) {
                resumed++
            }
        }
        val access = StudioHumanQueue(records, queue, lazy { error("Enqueue never opens a handle") })
        access.enqueue(
            "child",
            StudioQueuedMessage("message", "Continue", emptyList(), DefaultRunSettings),
            null,
            false,
        )
        access.manage("child", StudioQueueAction.Resume, "")
        assertEquals(1, enqueued)
        assertEquals(1, resumed)
    }

    private fun records(): StudioChatRecords = object : StudioChatRecords {
        override val store = ChecklistTestStore(ChatSpec)
        override val lock = Mutex()
    }
}
