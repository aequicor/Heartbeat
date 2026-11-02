package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.organism
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OrganicNativeJournalTest {
    @Test
    fun `full conversation claims reject noncanonical member addresses`() {
        val address = organism().cellExecution(CellId.ZYGOTE)!!.instance.id
        assertFailsWith<IllegalArgumentException> {
            OrganicNativeClaim("claim", " $address", null, TARGET, null, RequestId("r"), "work")
        }
    }

    @Test
    fun `unknown outcome needs an independent stop proof and cannot impersonate native terminal`() {
        val binding = organism().cellExecution(CellId.ZYGOTE)!!
        val claim = OrganicNativeClaim("claim", binding.instance.id, binding, TARGET, null, RequestId("r"), "work")
        val accepted = OrganicNativeAttempt(
            claim,
            OrganicNativeStage.Accepted,
            session("native"),
            TurnId("turn"),
        )
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(
                stage = OrganicNativeStage.Settled,
                outcome = TurnOutcome.Unknown,
                end = OrganicNativeEnd.NativeTerminal,
            )
        }
        assertTrue(
            accepted.copy(
                stage = OrganicNativeStage.Settled,
                outcome = TurnOutcome.Unknown,
                end = OrganicNativeEnd.ConfirmedStop,
            ).isQuiescent,
        )
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(
                stage = OrganicNativeStage.Settled,
                outcome = TurnOutcome.Completed,
                end = OrganicNativeEnd.NativeIdle,
            )
        }
    }
}
