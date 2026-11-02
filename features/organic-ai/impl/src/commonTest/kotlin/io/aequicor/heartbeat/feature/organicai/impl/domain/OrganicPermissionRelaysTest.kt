package io.aequicor.heartbeat.feature.organicai.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.organicai.impl.TARGET
import io.aequicor.heartbeat.feature.organicai.impl.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OrganicPermissionRelaysTest {
    private val address = OrganicExecutorAddress("organism", "incarnation", OrganicExecutorAddress.Kind.Cell, "cell")
    private val owner = LogicalSessionContext(LogicalSessionId("owner"), SessionKind.Session)
    private val binding = SessionExecutionBinding(address.instance(), owner.subagent(LogicalSessionId("cell")))
    private val claim = OrganicNativeClaim(
        "claim",
        address.instance().id,
        binding,
        TARGET,
        null,
        RequestId("R"),
        "Work",
    )
    private val ref = session("native")
    private val permission = PermissionRequest(
        PermissionRequestId("approval"),
        TurnId("turn"),
        "Write file",
        listOf(PermissionOption(PermissionOptionId("once"), "Once", isOneTime = true)),
    )

    @Test
    fun `late old observer cannot replace or forget a newer permission appearance`() = runTest {
        var current = RequestId("observer-A")
        val issuer = OrganicPermissionRelays { _, request -> request == current }
        assertTrue(issuer.begin(claim.address, "A", current))
        val old = issuer.issue(claim, ref, "A", listOf(permission)).single()
        current = RequestId("observer-B")
        assertTrue(issuer.begin(claim.address, "B", current))
        val fresh = issuer.issue(claim, ref, "B", listOf(permission)).single()
        assertNotEquals(old.nonce, fresh.nonce)
        assertTrue(issuer.issue(claim, ref, "A", listOf(permission)).isEmpty())
        issuer.forget(claim.address, "A")
        assertFalse(issuer.consume(old))
        assertTrue(issuer.consume(fresh))
        assertFalse(issuer.consume(fresh))
    }

    @Test
    fun `restart and changed pending operation require fresh appearances`() = runTest {
        val observer = RequestId("observer")
        val issuer = OrganicPermissionRelays { _, request -> request == observer }
        issuer.begin(claim.address, "A", observer)
        val old = issuer.issue(claim, ref, "A", listOf(permission)).single()
        val changed = issuer.issue(claim, ref, "A", listOf(permission.copy(title = "Changed arguments"))).single()
        assertFalse(issuer.consume(old))
        assertFalse(OrganicPermissionRelays { _, _ -> true }.consume(changed))
        issuer.issue(claim, ref, "A", emptyList())
        assertFalse(issuer.consume(changed))
    }
}
