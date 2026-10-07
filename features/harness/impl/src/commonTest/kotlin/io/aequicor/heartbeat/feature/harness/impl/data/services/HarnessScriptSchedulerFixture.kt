package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionDiscoveryReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstance
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRegistrationFixture
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.MemoryHarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeOperations
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakePort
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeQuotas
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessWakeReceipt
import io.aequicor.heartbeat.feature.scheduler.api.BusEvent
import io.aequicor.heartbeat.feature.scheduler.api.EventKey
import io.aequicor.heartbeat.feature.scheduler.api.EventOrigin
import io.aequicor.heartbeat.feature.scheduler.api.ScheduledWake
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerBus
import io.aequicor.heartbeat.feature.scheduler.api.SchedulerState
import io.aequicor.heartbeat.feature.scheduler.api.WakeRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

internal class HarnessScriptSchedulerFixture(scope: TestScope) {
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(scope.testScheduler.currentTime)
    }
    var externalReads = 0
    var isAllowed = true
    var allowsTarget: (HarnessTarget?) -> Boolean = { true }
    var admissionRevision = 0
    var beforePermit: suspend () -> Unit = {}
    val targets = mutableListOf<HarnessTarget?>()
    val summary = MutableStateFlow(SessionSummary(dispatchSession, workspace = WorkspaceRef("checkout")))
    val ancestry = MemoryHarnessRequestAncestry()
    val port = SchedulerWakePortFixture()
    val published = mutableListOf<BusEvent>()
    private val access = HarnessSchedulerAccess { _, target ->
        targets += target
        val captured = admissionRevision
        flowOf(
            if (isAllowed && allowsTarget(target)) {
                HarnessDeliveryPermit {
                    beforePermit()
                    isAllowed && allowsTarget(target) && admissionRevision == captured
                }
            } else {
                null
            },
        )
    }
    private val bus = object : SchedulerBus {
        override val events = MutableSharedFlow<BusEvent>()
        override suspend fun publish(key: EventKey, origin: EventOrigin, payload: String?): BusEvent =
            BusEvent(key, origin, Instant.fromEpochMilliseconds(scope.testScheduler.currentTime), payload)
                .also(published::add)
    }
    val facade = SchedulerFacadeFixture(summary)
    val sessionAccess = EngineHarnessSessionAccess(
        lazy { runtime.runtime },
        lazy {
            externalReads++

            access
        },
        lazy {
            externalReads++

            facade
        },
    )
    val reader = EngineHarnessSessionReader(lazy { facade }, sessionAccess)
    private val host = EngineHarnessScheduler(
        sessionAccess,
        lazy {
            externalReads++

            bus
        },
        lazy {
            externalReads++

            port
        },
        lazy {
            externalReads++
            HarnessWakeOperations(
                scope.backgroundScope,
                port,
                HarnessWakeQuotas(clock),
                HarnessRequestOrigins(ancestry),
                clock,
            )
        },
    )
    val runtime: HarnessRegistrationFixture = HarnessRegistrationFixture(
        scope.backgroundScope,
        StandardTestDispatcher(scope.testScheduler),
        host,
    )
    val script get() = runtime.scripts.last()
    suspend fun activate(): HarnessInstance = runtime.activate()
}

internal class SchedulerWakePortFixture : HarnessWakePort {
    var ready = SchedulerState.Ready()
    var beforeSchedule: suspend () -> Unit = {}
    val scheduled = mutableListOf<WakeRequest>()
    val cancellations = mutableListOf<Pair<WakeRequest, EventOrigin?>>()
    override fun snapshot(): SchedulerState.Ready = ready
    override suspend fun schedule(request: WakeRequest, at: Instant): HarnessWakeReceipt {
        beforeSchedule()
        scheduled += request
        ready = ready.copy(wakes = ready.wakes + ScheduledWake(request, at))
        return HarnessWakeReceipt.Scheduled
    }
    override suspend fun cancel(request: WakeRequest, cause: EventOrigin?): Boolean {
        cancellations += request to cause
        val matches = ready.wakes.filter { it.request == request && it.id !in ready.delivering }
        ready = ready.copy(wakes = ready.wakes - matches.toSet())
        return matches.isNotEmpty()
    }
}

internal class SchedulerFacadeFixture(summary: MutableStateFlow<SessionSummary>) : EngineFacade {
    val summaries = mutableMapOf(summary.value.ref to summary)
    val pageRequests = mutableListOf<PageRequest>()
    var history: SessionHistory? = null
    var page: suspend (PageRequest) -> SessionPage = {
        SessionPage(summaries.values.map { it.value }, null, emptyList())
    }
    var beforeGet: suspend () -> Unit = {}
    private fun session(summary: MutableStateFlow<SessionSummary>) = object : EngineSession {
        override val summary = summary
        override val features = object : EngineFeatures {
            @Suppress("UNCHECKED_CAST")
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
                if (key == SessionHistory) {
                    history?.let { FeatureAccess.Available(it as F) } ?: FeatureAccess.Unsupported
                } else {
                    FeatureAccess.Unsupported
                }
        }
    }
    override val sessions = object : SessionCatalog {
        override suspend fun get(ref: SessionRef): EngineSession {
            beforeGet()
            return session(summaries[ref] ?: error("Unknown session"))
        }
        override suspend fun page(query: SessionQuery, request: PageRequest): SessionPage {
            pageRequests += request
            return page(request)
        }
        override suspend fun refresh(query: SessionQuery): SessionDiscoveryReport = error("Unexpected refresh")
    }
    override val engines: EngineCatalog get() = error("Unexpected engine lookup")
    override val bindings: EngineBindings get() = error("Unexpected credential lookup")
    override val models: ModelCatalog get() = error("Unexpected model lookup")
    override val providerUsage: ProviderUsageCatalog get() = error("Unexpected usage lookup")
}
