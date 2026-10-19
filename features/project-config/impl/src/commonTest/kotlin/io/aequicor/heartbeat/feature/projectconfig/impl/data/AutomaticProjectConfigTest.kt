package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorage
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorageSettings
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomaticProjectConfigTest {
    private val harnesses = FakeSection()
    private val interfaces = FakeSection(ConfigSectionId.Interfaces, parent = ConfigSectionId.Harnesses)
    private val fixture = EffectsFixture(harnesses, interfaces)

    @Test
    fun `owned harness and its interface are saved on first sync and edits are written back`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h", "review", "rules", projects = setOf(PROJECT)))
        interfaces.state.value = listOf(FakeEntity("ui", "review-board", "board", parent = "h"))
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        assertEquals(setOf("harnesses/review.md", "interfaces/review-board.md"), fixture.files.keys)
        assertEquals(2, fixture.storage.bindings.size)
        assertTrue(fixture.reviewer.changes.isEmpty())
        harnesses.state.value = listOf(FakeEntity("h", "review", "new rules", projects = setOf(PROJECT)))
        fixture.run(Internal.Changed(PROJECT))
        assertEquals("new rules\n", fixture.files["harnesses/review.md"])
    }

    @Test
    fun `shared entities stay in the app and one harness can belong to several projects`() = runTest {
        val other = WorkspaceRef("other")
        harnesses.state.value = listOf(
            FakeEntity("shared", "shared", "global"),
            FakeEntity("h", "review", "rules", projects = setOf(PROJECT, other)),
        )
        val reader = fixture.access.reader
        val snapshot = reader.sections()
        assertEquals(
            "review",
            reader.entities(snapshot, PROJECT).digests.single { it.automaticUnit != null }.automaticUnit?.name,
        )
        assertEquals(
            "review",
            reader.entities(snapshot, other).digests.single { it.automaticUnit != null }.automaticUnit?.name,
        )
        assertTrue(reader.entities(snapshot, WorkspaceRef("unrelated")).digests.all { it.automaticUnit == null })
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        assertEquals(setOf("harnesses/review.md"), fixture.files.keys)
    }

    @Test
    fun `app default and project override survive reloading and app mode performs no project IO`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h", "review", "rules", projects = setOf(PROJECT)))
        fixture.storage.settings = ConfigStorageSettings(ConfigStorage.App)
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Public.Sync(PROJECT))
        assertEquals(0, fixture.folders.reads)
        assertEquals(0, fixture.folders.fingerprints)
        assertNull(fixture.access.fingerprint(PROJECT))
        assertTrue(fixture.folders.trees.isEmpty())
        fixture.run(Internal.SetProjectStorage(PROJECT, ConfigStorage.Project))
        assertEquals("rules\n", fixture.files["harnesses/review.md"])
        fixture.run(Internal.Disabled)
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        assertEquals(ConfigStorage.Project, fixture.ready.storage.location(PROJECT))
        fixture.run(Internal.SetProjectStorage(PROJECT, null))
        assertEquals(ConfigStorage.App, fixture.ready.storage.location(PROJECT))
        val reads = fixture.folders.reads
        val prints = fixture.folders.fingerprints
        fixture.run(Internal.Changed(PROJECT))
        fixture.access.folder(PROJECT)
        fixture.access.fingerprint(PROJECT)
        assertEquals(reads, fixture.folders.reads)
        assertEquals(prints, fixture.folders.fingerprints)
        assertTrue(fixture.ready.proposals.isEmpty())
    }

    @Test
    fun `explicit removal is durable and manual saving restores automatic synchronization`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h", "review", "rules", projects = setOf(PROJECT)))
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Internal.RemoveFromProject(PROJECT, ConfigUnitRef(ConfigSectionId.Harnesses, "review")))
        assertTrue(fixture.files.isEmpty())
        assertTrue(fixture.storage.bindings.single().isExcluded)
        fixture.run(Internal.Disabled)
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        assertTrue(fixture.files.isEmpty())
        fixture.run(
            Internal.SaveToProject(
                "save",
                PROJECT,
                listOf(ConfigEntityRef(ConfigSectionId.Harnesses, ConfigEntityKey("h"))),
            ),
        )
        assertFalse(fixture.storage.bindings.single().isExcluded)
        assertEquals("rules\n", fixture.files["harnesses/review.md"])
    }

    @Test
    fun `app choice waits for an active physical write then fences subsequent IO`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = async {
            fixture.policy.access(PROJECT) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val save = async { fixture.policy.save(ConfigStorageSettings(ConfigStorage.App)) }
        runCurrent()
        assertFalse(save.isCompleted)
        release.complete(Unit)
        write.await()
        save.await()
        assertNull(fixture.policy.access(PROJECT) { error("Project IO after App was saved") })
    }
}
