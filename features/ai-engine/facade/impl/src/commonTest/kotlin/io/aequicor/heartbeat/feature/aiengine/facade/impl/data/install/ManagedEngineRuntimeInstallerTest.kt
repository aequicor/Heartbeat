package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityStamp
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.identity
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.FakeEngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.registration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ManagedEngineRuntimeInstallerTest {
    private val engine = EngineId("kimi")
    private val protocol = EngineProtocolIdentity.Negotiated("test", 1)
    private val stamp = EngineCompatibilityStamp(engine, "host", "connector", "1.0.0", protocol, 1, 1, "target", "hash")
    private val policy = EngineCompatibilityRequirements(
        engine, setOf("host"), setOf("connector"), "1.0.0", protocols = setOf(protocol),
        configurationSchemas = setOf(1), historySchemas = setOf(1), nativeTargets = setOf("target"),
        baselineCapabilities = emptySet(), verified = setOf(stamp),
    )
    private val factory = FakeEngineFactory().apply { runtimeRequirements = policy }
    private val registry = EngineRegistry(
        listOf(registration(factory = factory, id = engine)),
        EnginePlatform.DesktopMacOs,
    )
    private val copy = ManagedInstall("1.0.0", "/retained/kimi", Instant.fromEpochSeconds(0))
    private val plan = InstallPlan(
        "1.0.0",
        "https://example.com/kimi.zip",
        "a".repeat(64),
        10,
        ArchiveKind.Zip(),
        "kimi",
        setOf("example.com"),
    )
    private val feeds = object : ReleaseFeeds {
        override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = error("offline")
        override suspend fun document(url: String, allowedHosts: Set<String>): String = error("offline")
    }

    @Test
    fun `rollback reuses retained version offline and never resolves a release`() = runTest {
        val store = Copies(copy)
        val manager = manager(resolve = { error("must stay offline") })
        assertEquals(copy, ManagedEngineRuntimeInstaller(store, feeds, registry).prepare(engine, "1.0.0", manager) {})
        assertFalse(store.isActivated)
        assertFalse(store.isStaged)
    }

    @Test
    fun `first use verifies staged copy before publishing it and retains it`() = runTest {
        val store = Copies()
        val phases = mutableListOf<EnginePreparation>()
        val installer = ManagedEngineRuntimeInstaller(store, feeds, registry)
        assertEquals(copy, installer.prepare(engine, "1.0.0", manager(), progress = phases::add))
        assertTrue(store.isActivated)
        assertTrue(store.isRetained)
        assertEquals(listOf<EnginePreparation>(EnginePreparation.Installing, EnginePreparation.Installing), phases)
    }

    @Test
    fun `wrong pinned release leaves existing copies untouched`() = runTest {
        val store = Copies()
        assertFailsWith<ManagementException> {
            ManagedEngineRuntimeInstaller(store, feeds, registry).prepare(engine, "2.0.0", manager()) {}
        }
        assertFalse(store.isStaged)
        assertFalse(store.isActivated)
    }

    @Test
    fun `failed candidate is discarded and cannot replace a working version`() = runTest {
        val store = Copies()
        val rejected = manager(inspect = { null })
        assertFailsWith<ManagementException> {
            ManagedEngineRuntimeInstaller(store, feeds, registry).prepare(engine, "1.0.0", rejected) {}
        }
        assertTrue(store.isDiscarded)
        assertFalse(store.isActivated)
    }

    @Test
    fun `cancelled check discards stage and propagates cancellation`() = runTest {
        val store = Copies()
        val cancelled = manager(inspect = { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> {
            ManagedEngineRuntimeInstaller(store, feeds, registry).prepare(engine, "1.0.0", cancelled) {}
        }
        assertTrue(store.isDiscarded)
        assertFalse(store.isActivated)
    }

    private fun manager(
        resolve: suspend () -> InstallPlan = { plan },
        inspect: suspend () -> EngineCompatibilityObservation? = { observation() },
    ) = object : EngineManager {
        override suspend fun inspect(launch: LaunchContext): Installation = error("ordinary inspection is not allowed")
        override suspend fun inspectCompatibility(installation: Installation, launch: LaunchContext) = inspect()
        override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan = resolve()
    }

    @Test
    fun `retained and staged copies cannot self approve without connector policy`() = runTest {
        factory.runtimeRequirements = null
        val store = Copies(copy)
        assertFailsWith<ManagementException> {
            ManagedEngineRuntimeInstaller(store, feeds, registry).prepare(engine, "1.0.0", manager()) {}
        }
        assertFalse(store.isActivated)
        assertTrue(store.isDiscarded)
    }

    @Test
    fun `report policy cannot approve a copy outside registered regressions`() = runTest {
        factory.runtimeRequirements = policy.copy(verified = emptySet())
        val store = Copies()
        assertFailsWith<ManagementException> {
            ManagedEngineRuntimeInstaller(store, feeds, registry).prepare(engine, "1.0.0", manager()) {}
        }
        assertFalse(store.isActivated)
        assertTrue(store.isDiscarded)
    }

    private fun observation(): EngineCompatibilityObservation = EngineCompatibilityObservation(
        policy,
        EngineCompatibilityEvidence(stamp.identity(), true, true, emptySet()),
    )

    private inner class Copies(private val saved: ManagedInstall? = null) : ManagedInstallStore {
        override val state = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())
        var isStaged = false
        var isActivated = false
        var isDiscarded = false
        var isRetained = false
        override suspend fun refresh() = Unit
        override suspend fun retained(engine: EngineId, version: String, target: String?): ManagedInstall? =
            saved?.takeIf { it.version == version }
        override suspend fun stage(
            engine: EngineId,
            plan: InstallPlan,
            progress: suspend (InstallStep) -> Unit,
        ): StagedInstall {
            isStaged = true
            isRetained = plan.isRetainedForRollback
            progress(InstallStep.Unpacking)
            return StagedInstall(engine, copy, "token", plan.sha256)
        }
        override suspend fun activate(staged: StagedInstall): ManagedInstall {
            isActivated = true
            return copy
        }
        override suspend fun discard(staged: StagedInstall) {
            isDiscarded = true
        }
        override suspend fun uninstall(engine: EngineId) = Unit
    }
}
