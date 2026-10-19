package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigStorageTest {
    private val project = WorkspaceRef("p")
    private val other = WorkspaceRef("other")
    private val unit = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val key = ConfigEntityKey("review")
    private val digest = ConfigDigest("digest")
    private val owned = EntityDigest(unit.section, key, "review", digest, unit)

    @Test
    fun `project is the default and overrides can restore inheritance`() {
        assertEquals(ConfigStorage.Project, ConfigStorageSettings().location(project))
        val settings = ConfigStorageSettings(ConfigStorage.App, mapOf(project.value to ConfigStorage.Project))
        assertEquals(ConfigStorage.Project, settings.location(project))
        assertEquals(ConfigStorage.App, settings.location(other))
        assertEquals(ConfigStorage.App, settings.copy(projects = emptyMap()).location(project))
    }

    @Test
    fun `owned entities without files are written but shared ones are not`() {
        val snapshot = ProjectSnapshot(project, 1, emptyList(), listOf(owned))
        assertEquals(listOf(ConfigWriteBack(project, unit, key, null)), reconcile(emptyList(), snapshot).writeBacks)
        val shared = snapshot.copy(entities = listOf(owned.copy(automaticUnit = null)))
        assertTrue(reconcile(emptyList(), shared).writeBacks.isEmpty())
        assertTrue(reconcile(emptyList(), snapshot.copy(unavailable = setOf(unit.section))).writeBacks.isEmpty())
    }

    @Test
    fun `existing files and excluded entities are never automatically replaced`() {
        val snapshot = ProjectSnapshot(project, 1, emptyList(), listOf(owned))
        val existing = ScannedUnit(
            unit,
            ConfigDigest("external"),
            UnitCheck.Valid(ConfigSummary("review"), "review", ConfigDigest("external")),
        )
        val conflict = reconcile(emptyList(), snapshot.copy(units = listOf(existing)))
        assertTrue(conflict.writeBacks.isEmpty())
        assertEquals(ProposalKind.Update, conflict.proposals.single().kind)
        val excluded = ConfigBinding(project, unit, key, digest, digest, isExcluded = true)
        val result = reconcile(listOf(excluded), snapshot)
        assertEquals(listOf(excluded), result.bindings)
        assertTrue(result.writeBacks.isEmpty())
        assertTrue(result.proposals.isEmpty())
        val restored = reconcile(listOf(excluded), snapshot.copy(units = listOf(existing)))
        assertEquals(ProposalKind.Update, restored.proposals.single().kind)
        assertTrue(restored.writeBacks.isEmpty())
        val renamed = existing.copy(ref = ConfigUnitRef(unit.section, "custom"))
        val incoming = reconcile(emptyList(), snapshot.copy(units = listOf(renamed)))
        assertEquals(key, incoming.proposals.single().target)
        assertTrue(incoming.writeBacks.isEmpty())
    }

    @Test
    fun `app projects are listed without syncing or proposing removals`() {
        val settings = ConfigStorageSettings(ConfigStorage.App)
        ProjectConfigMachineSpec.assertTransition(
            from = ProjectConfigState.Loading(setOf(project)),
            intent = ProjectConfigIntent.Internal.Loaded(emptyList(), settings),
            to = ProjectConfigState.Ready(projects = mapOf(project to ProjectSync(generation = 1)), storage = settings),
        )
        val ready = ProjectConfigState.Ready(projects = mapOf(project to ProjectSync()), storage = settings)
        ProjectConfigMachineSpec.assertTransition(ready, ProjectConfigIntent.Public.Sync(project), ready)
        ProjectConfigMachineSpec.assertIgnored(
            ready,
            ProjectConfigIntent.Internal.ImportToProject("r", "file.hbai", project),
        )
    }

    @Test
    fun `a location change exits ready to cancel old effects before persisting`() {
        val ready = ProjectConfigState.Ready(projects = mapOf(project to ProjectSync(isSyncing = true)))
        ProjectConfigMachineSpec.assertTransition(
            ready,
            ProjectConfigIntent.Internal.SetDefaultStorage(ConfigStorage.App),
            ProjectConfigState.Configuring(setOf(project)),
            effects = listOf(ProjectConfigEffect.SaveStorage(ConfigStorageSettings(ConfigStorage.App), emptyList())),
        )
        ProjectConfigMachineSpec.assertTransition(
            ProjectConfigState.Configuring(setOf(project)),
            ProjectConfigIntent.Internal.StorageSaved,
            ProjectConfigState.Loading(setOf(project)),
            effects = listOf(ProjectConfigEffect.Load),
        )
        ProjectConfigMachineSpec.assertTransition(
            ProjectConfigState.Configuring(setOf(project)),
            ProjectConfigIntent.Internal.StorageSaveFailed,
            ProjectConfigState.Loading(setOf(project)),
            effects = listOf(ProjectConfigEffect.Load),
            outputs = listOf(ProjectConfigOutput.StorageFailed),
        )
        ProjectConfigMachineSpec.assertIgnored(
            ProjectConfigState.Configuring(setOf(project)),
            ProjectConfigIntent.Internal.Synced(ProjectSnapshot(project, 1, emptyList(), emptyList())),
        )
    }
}
