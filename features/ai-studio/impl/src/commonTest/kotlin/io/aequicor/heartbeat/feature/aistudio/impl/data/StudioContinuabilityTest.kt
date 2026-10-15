package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioContinuabilityTest {
    @Test
    fun `a failed close retains the handle until retry releases it`() = runTest {
        val handle = ContinuitySession(ActiveSessionState.Closing())
        handle.closeFailure = IllegalStateException("close failed")
        val handles = mutableMapOf<String, ActiveSession>("chat" to handle)
        kotlin.test.assertFailsWith<IllegalStateException> { handles.live("chat") }
        assertEquals(handle, handles["chat"])
        handle.closeFailure = null
        assertNull(handles.live("chat"))
        assertEquals(2, handle.closes)
        assertTrue(handles.isEmpty())
    }

    @Test
    fun `a live handle keeps its conversation continuable without probing the stored session`() = runTest {
        val facade = ContinuityFacade(isResumable = false)
        val probes = StudioContinuability(facade) { ContinuitySession(ActiveSessionState.Ready()) }

        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(0, facade.lookups)
    }

    @Test
    fun `a released handle defers to the stored session instead of locking the conversation`() = runTest {
        val facade = ContinuityFacade(isResumable = true)
        val probes = StudioContinuability(facade) { ContinuitySession(ActiveSessionState.Closed) }

        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(1, facade.lookups)
    }

    @Test
    fun `a closing handle over an unresumable stored session is not continuable`() = runTest {
        val facade = ContinuityFacade(isResumable = false)
        val probes = StudioContinuability(facade) { ContinuitySession(ActiveSessionState.Closing()) }

        assertFalse(probes.isContinuable("chat", StoredRef))
    }

    @Test
    fun `stored probes are reused until the enabled engines change`() = runTest {
        val facade = ContinuityFacade(isResumable = true)
        val probes = StudioContinuability(facade) { null }

        assertTrue(probes.isContinuable("chat", StoredRef))
        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(1, facade.lookups)

        facade.engines.state.value = emptyList()
        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(2, facade.lookups)
    }

    @Test
    fun `a disabled engine is probed once until the enabled engines change`() = runTest {
        val facade = ContinuityFacade(isResumable = true)
        facade.engines.state.value = emptyList()
        facade.failure = EngineFailure.Engine(EngineFailureReason.Unavailable)
        val probes = StudioContinuability(facade) { null }

        assertFalse(probes.isContinuable("chat", StoredRef))
        assertFalse(probes.isContinuable("chat", StoredRef))
        assertEquals(1, facade.lookups)

        facade.failure = null
        facade.engines.state.value = listOf(ContinuityInfo)
        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(2, facade.lookups)
    }

    @Test
    fun `an enabled engine that is unavailable for now is probed again`() = runTest {
        val facade = ContinuityFacade(isResumable = true)
        facade.failure = EngineFailure.Engine(EngineFailureReason.Unavailable)
        val probes = StudioContinuability(facade) { null }

        assertFalse(probes.isContinuable("chat", StoredRef))
        facade.failure = null
        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(2, facade.lookups)
    }

    @Test
    fun `download progress does not change the recompute key but readiness does`() {
        fun key(info: EngineInfo) = listOf(info).continuabilityKey()
        val downloading = ContinuityInfo.copy(preparation = EnginePreparation.Downloading(1, 10))

        assertEquals(key(downloading), key(downloading.copy(preparation = EnginePreparation.Downloading(9, 10))))
        assertNotEquals(key(downloading), key(ContinuityInfo))
        assertNotEquals(key(ContinuityInfo), key(ContinuityInfo.copy(availability = EngineAvailability.Unknown)))
    }

    @Test
    fun `a transient failure is probed again on the next recompute`() = runTest {
        val facade = ContinuityFacade(isResumable = true)
        facade.failure = EngineFailure.Engine(EngineFailureReason.Crashed)
        val probes = StudioContinuability(facade) { null }

        assertFalse(probes.isContinuable("chat", StoredRef))
        facade.failure = null
        assertTrue(probes.isContinuable("chat", StoredRef))
        assertEquals(2, facade.lookups)
    }

    @Test
    fun `a released handle is dropped so the conversation resumes instead of reusing it`() = runTest {
        val open = ContinuitySession(ActiveSessionState.Ready())
        val closed = ContinuitySession(ActiveSessionState.Closed)
        val handles = mutableMapOf<String, ActiveSession>("open" to open, "closed" to closed)

        assertEquals(open, handles.live("open"))
        assertNull(handles.live("closed"))
        assertEquals(setOf("open"), handles.keys)
        assertNull(handles.live("unknown"))
    }

    @Test
    fun `a conversation without a stored session is always continuable`() = runTest {
        val facade = ContinuityFacade(isResumable = false)

        assertTrue(StudioContinuability(facade) { null }.isContinuable("chat", null))
        assertEquals(0, facade.lookups)
    }
}

private val ContinuityEngine = EngineId("continuity")
private val StoredRef = SessionRef(ContinuityEngine, SessionSourceId("history"), "native")
private val ContinuityInfo = EngineInfo(
    EngineDescriptor(
        ContinuityEngine,
        "Continuity",
        EngineFamily.Vendor,
        setOf(EnginePlatform.DesktopMacOs),
        FeatureToggle.Flag("continuity.engine", "Continuity"),
    ),
    EngineAvailability.Available,
    emptyList(),
)

private class ContinuityFacade(private val isResumable: Boolean) : EngineFacade {
    var lookups = 0
    var failure: EngineFailure? = null
    override val engines = ContinuityEngines()
    override val bindings: EngineBindings get() = error("Continuability never reads bindings")
    override val models: ModelCatalog get() = error("Continuability never discovers models")
    override val providerUsage: ProviderUsageCatalog get() = error("Continuability never reads usage")
    override val sessions: SessionCatalog = object : SessionCatalog {
        override suspend fun page(query: SessionQuery, request: PageRequest) = error("unused")

        override suspend fun get(ref: SessionRef): EngineSession {
            lookups++
            failure?.let { throw EngineException(it) }
            return ContinuityStored(ref, isResumable)
        }

        override suspend fun refresh(query: SessionQuery) = error("unused")
    }
}

private class ContinuityEngines : EngineCatalog {
    override val state = MutableStateFlow(listOf(ContinuityInfo))

    override suspend fun refresh(engine: EngineId) = error("unused")
    override fun features(engine: EngineId): EngineFeatures = error("unused")
}

private class ContinuityStored(ref: SessionRef, isResumable: Boolean) : EngineSession {
    private val resume = object : ResumesSessions {
        override suspend fun resume(request: ResumeSessionRequest): ActiveSession = error("Probes never resume")
    }
    override val summary: StateFlow<SessionSummary> = MutableStateFlow(SessionSummary(ref))
    override val features = object : EngineFeatures {
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> = when {
            isResumable && key == ResumesSessions -> key.type.safeCast(resume)?.let { FeatureAccess.Available(it) }
                ?: FeatureAccess.Unsupported

            else -> FeatureAccess.Unsupported
        }
    }
}

private class ContinuitySession(initial: ActiveSessionState) : ActiveSession {
    var closeFailure: Exception? = null
    var closes = 0
    override val ref = StoredRef
    override val route: ExecutionRoute get() = error("Continuability never reads the route")
    override val state = MutableStateFlow(initial)
    override val features: EngineFeatures get() = error("Continuability never resolves handle features")
    override suspend fun close() {
        closes++
        closeFailure?.let { throw it }
        state.value = ActiveSessionState.Closed
    }
}
