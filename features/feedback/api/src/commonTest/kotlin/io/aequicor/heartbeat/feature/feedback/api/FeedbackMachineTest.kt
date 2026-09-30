package io.aequicor.heartbeat.feature.feedback.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import kotlin.test.Test
import kotlin.time.Instant

class FeedbackMachineTest {
    private val pending = record("first")
    private val applied = pending.copy(
        revision = 1,
        outcome = FeedbackOutcome.Applied(SessionConfiguration(ModelId("model"), trust = TrustLevel.Full)),
    )

    @Test
    fun `publication is queued before stored history arrives`() {
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Loading(),
            intent = FeedbackIntent.Public.Publish(pending),
            to = FeedbackState.Loading(listOf(pending)),
        )
    }

    @Test
    fun `restored history precedes reports queued during loading`() {
        val stored = applied.copy(id = "older")
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Loading(listOf(pending)),
            intent = FeedbackIntent.Internal.Loaded(listOf(stored)),
            to = FeedbackState.Ready(listOf(stored, pending)),
        )
    }

    @Test
    fun `pending stored operation becomes unknown without replaying it`() {
        val unknown = pending.copy(revision = 1, outcome = FeedbackOutcome.Unknown)
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Loading(),
            intent = FeedbackIntent.Internal.Loaded(listOf(pending)),
            to = FeedbackState.Ready(listOf(unknown)),
        )
    }

    @Test
    fun `live acknowledgement wins a tie with the restored unknown revision`() {
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Loading(listOf(applied)),
            intent = FeedbackIntent.Internal.Loaded(listOf(pending)),
            to = FeedbackState.Ready(listOf(applied)),
        )
    }

    @Test
    fun `operation update preserves its first position timestamp and anchor`() {
        val first = pending.copy(anchor = FeedbackAnchor(after = ItemId("original")))
        val later = record("second")
        val moved = applied.copy(
            createdAt = Instant.parse("2026-10-01T10:00:00Z"),
            anchor = FeedbackAnchor(after = ItemId("later")),
        )
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Ready(listOf(first, later)),
            intent = FeedbackIntent.Public.Publish(moved),
            to = FeedbackState.Ready(
                listOf(moved.copy(createdAt = first.createdAt, anchor = first.anchor), later),
            ),
        )
    }

    @Test
    fun `same or older revisions are ignored in both machine states`() {
        FeedbackMachineSpec.assertIgnored(
            FeedbackState.Loading(listOf(applied)),
            FeedbackIntent.Public.Publish(pending),
        )
        FeedbackMachineSpec.assertIgnored(FeedbackState.Ready(listOf(applied)), FeedbackIntent.Public.Publish(applied))
    }

    @Test
    fun `operation cannot move to another logical chat`() {
        FeedbackMachineSpec.assertIgnored(
            FeedbackState.Ready(listOf(pending)),
            FeedbackIntent.Public.Publish(applied.copy(source = "other-chat")),
        )
    }

    @Test
    fun `later provider rejection replaces acknowledgement in place`() {
        val failed = applied.copy(
            revision = 2,
            outcome = FeedbackOutcome.Failed(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability)),
        )
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Ready(listOf(applied)),
            intent = FeedbackIntent.Public.Publish(failed),
            to = FeedbackState.Ready(listOf(failed)),
        )
    }

    @Test
    fun `load failure exposes live queue and storage notification`() {
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Loading(listOf(pending)),
            intent = FeedbackIntent.Internal.LoadFailed,
            to = FeedbackState.Ready(listOf(pending)),
            outputs = listOf(FeedbackOutput.StorageFailed),
        )
    }

    @Test
    fun `save failure leaves configuration outcome intact`() {
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Ready(listOf(applied)),
            intent = FeedbackIntent.Internal.SaveFailed,
            to = FeedbackState.Ready(listOf(applied)),
            outputs = listOf(FeedbackOutput.StorageFailed),
        )
    }

    @Test
    fun `recovered history is merged with newer live entries without reverting them`() {
        val other = applied.copy(id = "older", source = "another-chat")
        FeedbackMachineSpec.assertTransition(
            from = FeedbackState.Ready(listOf(applied)),
            intent = FeedbackIntent.Internal.Loaded(listOf(other, pending)),
            to = FeedbackState.Ready(listOf(other, applied)),
        )
    }

    private fun record(id: String) = FeedbackRecord(
        id = id,
        source = "chat",
        revision = 0,
        createdAt = Instant.parse("2026-09-30T10:00:00Z"),
        change = FeedbackChange.Trust(TrustLevel.Ask, TrustLevel.Full),
        outcome = FeedbackOutcome.Pending,
    )
}
