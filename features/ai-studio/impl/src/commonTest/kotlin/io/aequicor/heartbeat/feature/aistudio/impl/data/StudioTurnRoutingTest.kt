package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExactRouteReadiness
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteUnavailableReason
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class StudioTurnRoutingTest {
    private val route = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))
    private val sibling = EngineTarget(EngineId("engine"), EngineBindingId("sibling"), ModelId("sibling-model"))
    private val selection = ModelSelection(
        bindings = listOf(
            BindingModels(route.binding, setOf(route.model)),
            BindingModels(sibling.binding, setOf(sibling.model)),
        ),
        defaultTarget = sibling,
    )
    private val record = StudioChatRecord("chat", "Chat", Instant.DISTANT_PAST, target = route)
    private val settings = RunSettings(
        modelId = route.studioModelId(),
        effort = ReasoningEffort.High,
        approval = ApprovalMode.Ask,
    )

    @Test
    fun `a confirmed exact route is accepted`() {
        val readiness = RoutingReadiness(route to RouteAvailability.Available)

        assertEquals(route, studioRunTarget(selection, record, null, settings, readiness))
    }

    @Test
    fun `an unbound route is refused instead of falling back to the default or a sibling binding`() {
        val readiness = RoutingReadiness(
            route to RouteAvailability.Unavailable(RouteUnavailableReason.BindingMissing),
            sibling to RouteAvailability.Available,
        )

        val failure = assertFailsWith<EngineException> {
            studioRunTarget(selection, record, null, settings, readiness)
        }

        assertEquals(EngineFailure.Access(AccessFailureReason.OperationNotAllowed), failure.failure)
        assertEquals(sibling, selection.defaultTarget)
    }

    @Test
    fun `a changed source revision or an unconfirmed route is refused without substitution`() {
        listOf(
            RouteAvailability.Unavailable(RouteUnavailableReason.SourceRevisionChanged),
            RouteAvailability.Unavailable(RouteUnavailableReason.RuntimeClosed),
            RouteAvailability.Unknown,
        ).forEach { availability ->
            val readiness = RoutingReadiness(
                route to availability,
                sibling to RouteAvailability.Available,
            )

            assertFailsWith<EngineException> {
                studioRunTarget(selection, record, null, settings, readiness)
            }
        }
        assertEquals(setOf(route.model), selection.enabled(route.binding))
        assertEquals(sibling, selection.defaultTarget)
    }
}

private class RoutingReadiness(vararg states: Pair<EngineTarget, RouteAvailability>) : ExactRouteReadiness {
    override val routes = MutableStateFlow(
        states.associate { (target, availability) ->
            RouteKey(target.engine, target.binding) to availability
        },
    )
}
