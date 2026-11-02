package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstruction
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.HelperPrompt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioExecutionSnapshotTest {
    private val helper = HelperId("chat")
    private val request = RequestId("R")
    private val snapshot = StudioExecutionSnapshot(
        DefaultRunSettings,
        checkNotNull(NativeHelperFixture().record.target),
        "high",
        null,
        TrustedUserInstructions.Complete(listOf(TrustedUserInstruction(request, "original instruction")))
            .savedAuthority(),
    )

    @Test
    fun `future model changes keep the original connection while credential replacement is rejected`() {
        val record = NativeHelperFixture().record
        snapshot.requireContinuationConnection(record.copy(target = snapshot.target.copy(model = ModelId("future"))))
        assertFailsWith<IllegalStateException> {
            snapshot.requireContinuationConnection(
                record.copy(target = snapshot.target.copy(binding = EngineBindingId("replacement"))),
            )
        }
    }

    @Test
    fun `stale opening cannot alter a newer execution configuration mode`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        val admissions = StudioHelperPromptAdmissions(
            lazy { error("No native admission in this test") },
            lazy { emptySet() },
        )
        val gate = StudioHelperSubmission(attempts, admissions, helper, request)
        attempts.prepareConversation(helper, HelperPrompt(request, "prompt"))
        gate.requireOpening()
        attempts.requestStop(helper, request)
        attempts.prepareConversation(helper, HelperPrompt(RequestId("next"), "next prompt"))

        assertFailsWith<IllegalStateException> { gate.requireOpening() }
    }

    @Test
    fun `configuration and original authority survive restart before native entry`() = runTest {
        val stores = ChecklistTestStores()
        val attempts = StudioHelperAttempts(stores)
        attempts.prepareConversation(helper, HelperPrompt(request, "prompt"))
        assertTrue(attempts.begin(helper, request, "sender"))
        attempts.recordExecutionSnapshot(helper, request, "sender", snapshot)
        val restored = StudioHelperAttempts(stores)

        assertEquals(snapshot, restored.receipt(helper, request)?.executionSnapshot)
        assertNull(restored.receipt(helper, request)?.nativeEntryClaim)
        assertTrue(restored.enterNative(helper, request, "sender"))
        assertFailsWith<IllegalStateException> {
            restored.recordExecutionSnapshot(helper, request, "sender", snapshot)
        }
    }

    @Test
    fun `another sender and changed authority cannot replace the captured snapshot`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareConversation(helper, HelperPrompt(request, "prompt"))
        attempts.begin(helper, request, "sender")
        assertFailsWith<IllegalStateException> {
            attempts.recordExecutionSnapshot(helper, request, "other", snapshot)
        }
        attempts.recordExecutionSnapshot(helper, request, "sender", snapshot)
        val changed = snapshot.copy(authority = snapshot.authority.append(RequestId("H"), "later instruction"))
        assertFailsWith<IllegalStateException> {
            attempts.recordExecutionSnapshot(helper, request, "sender", changed)
        }
        assertEquals(snapshot, attempts.receipt(helper, request)?.executionSnapshot)
    }

    @Test
    fun `stop before snapshot prevents native entry and never fabricates authority`() = runTest {
        val attempts = StudioHelperAttempts(ChecklistTestStores())
        attempts.prepareConversation(helper, HelperPrompt(request, "prompt"))
        attempts.begin(helper, request, "sender")
        attempts.requestStop(helper, request)
        assertFailsWith<IllegalStateException> {
            attempts.recordExecutionSnapshot(helper, request, "sender", snapshot)
        }
        assertNull(attempts.receipt(helper, request)?.executionSnapshot)
        assertNull(attempts.receipt(helper, request)?.nativeEntryClaim)
        assertEquals(TrustedUserInstructions.Incomplete, TrustedUserInstructions.Incomplete.savedAuthority().snapshot())
    }
}
