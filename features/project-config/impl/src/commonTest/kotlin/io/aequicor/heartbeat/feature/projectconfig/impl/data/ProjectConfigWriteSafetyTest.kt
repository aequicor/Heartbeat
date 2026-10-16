package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEffect
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigMachineSpec
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectConfigWriteSafetyTest {
    private val section = FakeSection()
    private val fixture = EffectsFixture(section)

    @Test
    fun `automatic update preserves a file edited after the sync and reconciles a conflict`() = runTest {
        bind()
        section.state.value = listOf(FakeEntity("key", "review", "app edit", revision = 1))
        val write = pendingWrite(fixture)
        fixture.files[PATH] = "file edit\n"

        val result = assertIs<Internal.WriteBackConflict>(fixture.results(write).single())
        assertEquals("file edit\n", fixture.files[PATH])
        fixture.run(result)

        assertTrue(fixture.ready.writing.isEmpty())
        assertEquals(ProposalKind.Conflict, fixture.ready.proposals.single().kind)
        assertEquals("file edit\n", fixture.files[PATH])
    }

    @Test
    fun `automatic deletion preserves a file restored after the sync`() = runTest {
        bind()
        section.state.value = emptyList()
        val write = pendingWrite(fixture)
        fixture.files[PATH] = "restored edit\n"

        assertIs<Internal.WriteBackConflict>(fixture.results(write).single())
        assertEquals("restored edit\n", fixture.files[PATH])
    }

    @Test
    fun `write back reads the source after encoding suspends`() = runTest {
        var duringEncode: () -> Unit = {}
        val wrapped = object : ConfigSection by section {
            override suspend fun encode(key: ConfigEntityKey, name: String): ConfigUnit? =
                section.encode(key, name).also { duringEncode() }
        }
        val fixture = EffectsFixture(wrapped)
        bind(fixture)
        section.state.value = listOf(FakeEntity("key", "review", "app edit", revision = 1))
        val write = pendingWrite(fixture)
        duringEncode = { fixture.files[PATH] = "edit during encoding\n" }

        assertIs<Internal.WriteBackConflict>(fixture.results(write).single())
        assertEquals("edit during encoding\n", fixture.files[PATH])
    }

    @Test
    fun `an ignored invalid path does not prevent writing a valid unit`() = runTest {
        bind()
        fixture.folders.readProblems = listOf(ConfigProblem(ConfigProblemCode.Unsupported))
        section.state.value = listOf(FakeEntity("key", "review", "app edit", revision = 1))

        fixture.run(Internal.Changed(PROJECT))

        assertEquals("app edit\n", fixture.files[PATH])
        assertTrue(fixture.ready.writing.isEmpty())
        assertTrue(fixture.ready.proposals.isEmpty())
    }

    @Test
    fun `reenabling the feature persists new bindings from the restarted revision`() = runTest {
        bind()
        assertEquals(1, fixture.storage.bindings.size)
        fixture.run(Internal.Disabled)
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.files["harnesses/new.md"] = "new lesson\n"
        fixture.run(Internal.Changed(PROJECT))
        val proposal = fixture.ready.proposals.single()

        fixture.run(Internal.Trust(proposal.id, proposal.digest))

        assertEquals(2, fixture.storage.bindings.size)
        assertEquals(fixture.ready.bindings.toSet(), fixture.storage.bindings.toSet())
    }

    @Test
    fun `trusting an old removal preserves the entity when its files were restored`() = runTest {
        bind()
        fixture.files.remove(PATH)
        fixture.run(Internal.Changed(PROJECT))
        val removal = fixture.ready.proposals.single()
        assertEquals(ProposalKind.Remove, removal.kind)
        fixture.files[PATH] = "restored\n"

        assertEquals(
            listOf(Internal.ApplyFailed(removal.id, ConfigRejection.Conflict)),
            fixture.results(ProjectConfigEffect.Apply(removal)),
        )
        assertEquals("rules", section.state.value.orEmpty().single().text)
    }

    @Test
    fun `a removal cannot treat an unreadable ancestor as an absent unit`() = runTest {
        bind()
        fixture.files.remove(PATH)
        fixture.run(Internal.Changed(PROJECT))
        val removal = fixture.ready.proposals.single()
        fixture.folders.readProblems = listOf(
            ConfigProblem(ConfigProblemCode.Unsupported, ConfigPath("harnesses")),
        )

        assertEquals(
            listOf(Internal.ApplyFailed(removal.id, ConfigRejection.Conflict)),
            fixture.results(ProjectConfigEffect.Apply(removal)),
        )
        assertEquals("rules", section.state.value.orEmpty().single().text)
    }

    @Test
    fun `a removal cannot delete an entity when its project no longer resolves`() = runTest {
        bind()
        fixture.files.remove(PATH)
        fixture.run(Internal.Changed(PROJECT))
        val removal = fixture.ready.proposals.single().copy(source = ConfigSource.Project(WorkspaceRef("gone")))

        assertEquals(
            listOf(Internal.ApplyFailed(removal.id, ConfigRejection.Conflict)),
            fixture.results(ProjectConfigEffect.Apply(removal)),
        )
        assertEquals("rules", section.state.value.orEmpty().single().text)
    }

    private suspend fun bind(target: EffectsFixture = fixture) {
        section.state.value = listOf(FakeEntity("key", "review", "rules"))
        target.files[PATH] = "rules\n"
        target.run(Internal.Enabled(setOf(PROJECT)))
        assertEquals(1, target.ready.bindings.size)
    }

    /** Runs a sync up to the point where its write is queued, leaving a window for a concurrent source edit. */
    private suspend fun pendingWrite(target: EffectsFixture): ProjectConfigEffect.WriteBack {
        val start = checkNotNull(ProjectConfigMachineSpec.resolve(target.state, Public.Sync(PROJECT)))
        target.state = start.to
        val sync = start.effects.filterIsInstance<ProjectConfigEffect.Sync>().single()
        val result = target.results(sync).single()
        val synced = checkNotNull(ProjectConfigMachineSpec.resolve(target.state, result))
        target.state = synced.to
        return synced.effects.filterIsInstance<ProjectConfigEffect.WriteBack>().single()
    }

    private companion object {
        const val PATH = "harnesses/review.md"
    }
}
