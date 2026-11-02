package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSummary
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioRecoveryOpeningTest {
    @Test
    fun `failed or cancelled registration releases the resumed handle inside the ownership boundary`() = runTest {
        for (failure in listOf(
            IllegalStateException("Registration failed"),
            CancellationException("Registration cancelled"),
        )) {
            val f = RecoveryOpeningFixture()
            var isForgotten = false
            val dispatchers = object : DispatcherProvider {
                override val main: CoroutineDispatcher get() = error("unused")
                override val default: CoroutineDispatcher get() = error("unused")
                override val io: CoroutineDispatcher get() = error("unused")
            }
            val native = StudioNativeSessionOperations(StudioLearningPrompts(ChecklistTestToggles(), dispatchers))
            val storage = StudioSessionStorage(
                readHandle = { error("unused") },
                registerHandle = { _, _ -> throw failure },
                forgetHandle = { _, active ->
                    assertSame(f.active, active)
                    isForgotten = true
                },
                update = { _, _ -> error("Recovery does not update the target") },
                persistReference = native::persistReference,
                readRecord = { f.native.record },
            )

            assertSame(failure, assertFailsWith<Exception> { storage.rememberRecovery(f.native.record, f.active) })

            assertTrue(isForgotten)
            assertTrue(f.isClosed)
        }
    }

    @Test
    fun `a live recovery handle never negotiates model changes or new declarations`() = runTest {
        val f = RecoveryOpeningFixture()

        assertSame(f.active, f.opening.open(f.native.record, f.active))

        assertNull(f.lookedUp)
        assertNull(f.resumed)
        assertFalse(f.isClosed)
    }

    @Test
    fun `cold recovery resumes only the saved native reference and target`() = runTest {
        val f = RecoveryOpeningFixture()

        assertSame(f.active, f.opening.open(f.native.record, null))

        assertEquals(f.native.ref, f.lookedUp)
        assertEquals(f.native.target, f.resumed?.target)
        assertNull(f.resumed?.workspace)
        assertFalse(f.isClosed)
    }

    @Test
    fun `a stale live handle is never reused changed or closed by another recovery`() = runTest {
        val f = RecoveryOpeningFixture()
        f.reference = f.native.ref.copy(nativeId = "unrelated")

        assertFailsWith<IllegalStateException> { f.opening.open(f.native.record, f.active) }

        assertNull(f.lookedUp)
        assertFalse(f.isClosed)
    }

    @Test
    fun `a wrong resumed identity is released without creating a replacement`() = runTest {
        val f = RecoveryOpeningFixture()
        f.reference = f.native.ref.copy(nativeId = "wrong")

        assertFailsWith<IllegalStateException> { f.opening.open(f.native.record, null) }

        assertEquals(f.native.ref, f.lookedUp)
        assertTrue(f.isClosed)
    }

    @Test
    fun `a missing saved reference cannot create a native session`() = runTest {
        val f = RecoveryOpeningFixture()

        assertFailsWith<IllegalStateException> { f.opening.open(f.native.record.copy(ref = null), null) }

        assertNull(f.lookedUp)
        assertNull(f.resumed)
        assertFalse(f.isClosed)
    }

    @Test
    fun `a different workspace cannot be silently adopted`() = runTest {
        val f = RecoveryOpeningFixture()
        val other = object : ActiveSession by f.active {
            override val route = f.active.route.copy(workspace = WorkspaceRef("other"))
        }

        assertFailsWith<IllegalStateException> { f.opening.open(f.native.record, other) }

        assertNull(f.lookedUp)
        assertFalse(f.isClosed)
    }
}

private class RecoveryOpeningFixture {
    val native = NativeHelperFixture()
    var reference = native.ref
    var isClosed = false
    var lookedUp: SessionRef? = null
    var resumed: ResumeSessionRequest? = null
    val active = object : ActiveSession by native {
        override val ref get() = reference
        override val features: EngineFeatures get() = error("Recovery cannot configure or submit on the active handle")
        override suspend fun close() {
            isClosed = true
        }
    }
    private val resume = object : ResumesSessions {
        override suspend fun resume(request: ResumeSessionRequest): ActiveSession {
            resumed = request
            return active
        }
    }
    private val facade = object : EngineFacade {
        override val engines: EngineCatalog get() = error("Detached recovery never discovers engines")
        override val bindings: EngineBindings get() = error("Recovery never changes bindings")
        override val models: ModelCatalog get() = error("Recovery never discovers models")
        override val providerUsage: ProviderUsageCatalog get() = error("Recovery never discovers usage")
        override val sessions = object : SessionCatalog {
            override suspend fun page(query: SessionQuery, request: PageRequest) = error(
                "Recovery never lists sessions",
            )
            override suspend fun refresh(query: SessionQuery) = error("Recovery never discovers sessions")
            override suspend fun get(ref: SessionRef): EngineSession {
                lookedUp = ref
                return object : EngineSession {
                    override val summary = MutableStateFlow(SessionSummary(ref))
                    override val features = nativeFeatures(resume)
                }
            }
        }
    }
    private val workspaces = object : LocalWorkspaces {
        override val isAvailable = false
        override fun observe(): Flow<List<LocalWorkspace>> = error("Recovery never lists projects")
        override suspend fun register(directory: String): LocalWorkspace = error("Recovery never creates projects")
        override suspend fun resolve(ref: WorkspaceRef): String? = error("Detached recovery has no project")
    }
    val opening = StudioRecoveryOpening(facade, workspaces)
}
