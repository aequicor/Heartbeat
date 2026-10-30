package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.InstallStep
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.StagedInstall
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
    private val copy = ManagedInstall("1.0", "/retained/kimi", Instant.fromEpochSeconds(0))
    private val plan = InstallPlan(
        "1.0",
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
        assertEquals(copy, ManagedEngineRuntimeInstaller(store, feeds).prepare(engine, "1.0", manager) {})
        assertFalse(store.isActivated)
        assertFalse(store.isStaged)
    }

    @Test
    fun `first use verifies staged copy before publishing it and retains it`() = runTest {
        val store = Copies()
        val phases = mutableListOf<EnginePreparation>()
        val installer = ManagedEngineRuntimeInstaller(store, feeds)
        assertEquals(copy, installer.prepare(engine, "1.0", manager(), phases::add))
        assertTrue(store.isActivated)
        assertTrue(store.isRetained)
        assertEquals(listOf<EnginePreparation>(EnginePreparation.Installing, EnginePreparation.Installing), phases)
    }

    @Test
    fun `wrong pinned release leaves existing copies untouched`() = runTest {
        val store = Copies()
        assertFailsWith<ManagementException> {
            ManagedEngineRuntimeInstaller(store, feeds).prepare(engine, "2.0", manager()) {}
        }
        assertFalse(store.isStaged)
        assertFalse(store.isActivated)
    }

    @Test
    fun `failed candidate is discarded and cannot replace a working version`() = runTest {
        val store = Copies()
        val rejected = manager(inspect = { Installation(InstallSource.Missing, isRunnable = false) })
        assertFailsWith<ManagementException> {
            ManagedEngineRuntimeInstaller(store, feeds).prepare(engine, "1.0", rejected) {}
        }
        assertTrue(store.isDiscarded)
        assertFalse(store.isActivated)
    }

    @Test
    fun `cancelled check discards stage and propagates cancellation`() = runTest {
        val store = Copies()
        val cancelled = manager(inspect = { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> {
            ManagedEngineRuntimeInstaller(store, feeds).prepare(engine, "1.0", cancelled) {}
        }
        assertTrue(store.isDiscarded)
        assertFalse(store.isActivated)
    }

    private fun manager(
        resolve: suspend () -> InstallPlan = { plan },
        inspect: suspend () -> Installation = { Installation(InstallSource.Managed, "1.0", isRunnable = true) },
    ) = object : EngineManager {
        override suspend fun inspect(launch: LaunchContext): Installation = inspect()
        override suspend fun resolveRelease(feeds: ReleaseFeeds): InstallPlan = resolve()
    }

    private inner class Copies(private val saved: ManagedInstall? = null) : ManagedInstallStore {
        override val state = MutableStateFlow(emptyMap<EngineId, ManagedInstall>())
        var isStaged = false
        var isActivated = false
        var isDiscarded = false
        var isRetained = false
        override suspend fun refresh() = Unit
        override suspend fun retained(engine: EngineId, version: String): ManagedInstall? =
            saved?.takeIf { it.version == version }
        override suspend fun stage(
            engine: EngineId,
            plan: InstallPlan,
            progress: suspend (InstallStep) -> Unit,
        ):
            StagedInstall {
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
