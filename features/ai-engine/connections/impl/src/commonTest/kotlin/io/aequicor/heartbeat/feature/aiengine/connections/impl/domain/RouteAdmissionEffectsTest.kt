package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardEffect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ConnectWizardIntent
import io.aequicor.heartbeat.feature.aiengine.connections.api.WizardAdmission
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeAuthSources
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineFacade
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeEngineRouteAdmission
import io.aequicor.heartbeat.feature.aiengine.connections.impl.FakeModelSelections
import io.aequicor.heartbeat.feature.aiengine.connections.impl.KoogId
import io.aequicor.heartbeat.feature.aiengine.connections.impl.RecordingScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibilityProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteAdmission
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The wizard never starts or inspects a runtime itself: it only reports the facade's exact-route decision. */
class RouteAdmissionEffectsTest {
    private val facade = FakeEngineFacade()
    private val sources = FakeAuthSources()
    private val admission = FakeEngineRouteAdmission()
    private val selections = FakeModelSelections()
    private val scope = RecordingScope<ConnectWizardIntent>()
    private val binding = EngineBindingId("binding-1")

    private fun wizard() = ConnectWizardEffects(
        EngineServices(facade, sources, admission = admission),
        selections,
        FakeModelChecks(),
    )

    @Test
    fun `an allowed route is reported without discovering models`() = runTest {
        admission.result = RouteAdmission.Assessed(allowedDecision())
        wizard().handle(ConnectWizardEffect.AssessAdmission(KoogId, binding), scope)
        assertEquals(listOf(KoogId to binding), admission.calls)
        assertEquals(
            listOf<ConnectWizardIntent>(ConnectWizardIntent.Internal.AdmissionAssessed(WizardAdmission.Allowed)),
            scope.intents,
        )
        assertTrue(facade.calls.none { it == "discover" })
    }

    @Test
    fun `a refused route keeps its typed problems and is never allowed`() = runTest {
        admission.result = RouteAdmission.Assessed(
            EngineCompatibilityDecision(
                Compatibility.Incompatible,
                setOf(CompatibilityProblem.BaselineRejected, CompatibilityProblem.ProvenanceRejected),
            ),
        )
        wizard().handle(ConnectWizardEffect.AssessAdmission(KoogId, binding), scope)
        assertEquals(
            listOf<ConnectWizardIntent>(
                ConnectWizardIntent.Internal.AdmissionAssessed(
                    WizardAdmission.Refused(
                        setOf(CompatibilityProblem.BaselineRejected, CompatibilityProblem.ProvenanceRejected),
                    ),
                ),
            ),
            scope.intents,
        )
    }

    @Test
    fun `an unavailable assessment is unknown and not a permit`() = runTest {
        admission.result = RouteAdmission.Unavailable
        wizard().handle(ConnectWizardEffect.AssessAdmission(KoogId, binding), scope)
        assertEquals(
            listOf<ConnectWizardIntent>(
                ConnectWizardIntent.Internal.AdmissionAssessed(WizardAdmission.Unknown()),
            ),
            scope.intents,
        )
    }

    @Test
    fun `a failed probe reaches the machine as a failure`() = runTest {
        admission.failure = EngineException(EngineFailure.Session(SessionFailureReason.Changed))
        assertFailsWith<EngineException> {
            wizard().handle(ConnectWizardEffect.AssessAdmission(KoogId, binding), scope)
        }
        assertTrue(scope.intents.isEmpty())
    }

    private fun allowedDecision(): EngineCompatibilityDecision {
        val protocol = EngineProtocolIdentity.Negotiated("test", 1)
        val policy = EngineCompatibilityRequirements(
            KoogId,
            setOf("host"),
            setOf("connector"),
            "1.0.0",
            protocols = setOf(protocol),
            configurationSchemas = setOf(1),
            historySchemas = setOf(1),
            nativeTargets = setOf("target"),
            baselineCapabilities = emptySet(),
        )
        val evidence = EngineCompatibilityEvidence(
            EngineCompatibilityIdentity(KoogId, "host", "connector", "1.0.0", protocol, 1, 1, "target", "hash"),
            isBaselineConfirmed = true,
            isProvenanceConfirmed = true,
            capabilities = emptySet(),
        )
        return EngineCompatibilityObservation(policy, evidence).decision
    }
}
