package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.DisabledReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineEnablement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagement
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineManagementState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrustStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Engines, schemas and fake services shared by the control panel tests. */
internal object ControlTestData {
    val pi = EngineId("pi")
    val codex = EngineId("codex")
    val settings = ConfigDocumentId("settings")

    val schema = EngineConfigSchema(
        documents = listOf(
            ConfigDocumentSpec(
                settings,
                LocalizedText("Settings"),
                ConfigFormat.Json,
                ConfigScope.entries.toSet(),
                "s.json",
            ),
        ),
        pages = listOf(
            ConfigPage(
                "network",
                LocalizedText("Network", mapOf("ru" to "Сеть")),
                listOf(
                    ConfigSection(
                        "retry",
                        LocalizedText("Retry"),
                        listOf(
                            ConfigField(
                                "retries",
                                LocalizedText("Retries"),
                                ConfigBinding.JsonPath(settings, listOf("retry")),
                                ConfigFieldKind.Number(min = 0),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    fun managed(
        id: EngineId,
        title: String = id.value,
        reasons: Set<DisabledReason> = emptySet(),
        version: String? = "1.0.0",
    ) = ManagedEngine(
        EngineDescriptor(
            id,
            title,
            EngineFamily.Vendor,
            setOf(EnginePlatform.DesktopMacOs),
            FeatureToggle.Flag("ai.${id.value}", id.value),
        ),
        ManagementSpec(),
        EngineEnablement(isUserEnabled = DisabledReason.DisabledByUser !in reasons, reasons = reasons),
        EngineAvailability.Unknown,
        installation = InstallationState(current = version?.let { Installation(InstallSource.Bundled, it) }),
    )

    fun configuration(vararg engines: EngineId) = EngineConfigurationState(
        isEnabled = true,
        engines = engines.map { EngineConfigState(it, schema, EngineConfigValues(), ConfigRevision(0)) },
    )
}

internal class FakeManagement(initial: EngineManagementState = EngineManagementState.Off) : EngineManagement {
    override val state = MutableStateFlow(initial)
    val commands = mutableListOf<Pair<EngineId, EngineCommand>>()

    override suspend fun execute(engine: EngineId, command: EngineCommand) {
        commands += engine to command
    }
}

internal class FakeConfiguration(initial: EngineConfigurationState = EngineConfigurationState.Off) :
    EngineConfiguration {
    override val state = MutableStateFlow(initial)
    val calls = mutableListOf<String>()
    var failure: Exception? = null

    override suspend fun resources(engine: EngineId, workspace: WorkspaceRef?): ResourceInventory {
        calls += "resources"
        return ResourceInventory(engine, workspace, emptyList())
    }

    override suspend fun saveProfile(engine: EngineId, edit: ConfigEdit, expected: ConfigRevision): ConfigRevision {
        calls += "saveProfile"
        failure?.let { throw it }
        return ConfigRevision(expected.value + 1)
    }

    override suspend fun readProject(engine: EngineId, workspace: WorkspaceRef): ProjectConfig {
        calls += "readProject"
        return project(engine, workspace)
    }

    override suspend fun saveProject(
        engine: EngineId,
        workspace: WorkspaceRef,
        edit: ProjectEdit,
        expected: ProjectRevision,
    ): ProjectConfig {
        calls += "saveProject"
        return project(engine, workspace)
    }

    override suspend fun projectTrust(engine: EngineId, workspace: WorkspaceRef): ProjectTrustStatus {
        calls += "projectTrust"
        return ProjectTrustStatus(ProjectTrust.Undecided, hasGatedConfiguration = false)
    }

    override suspend fun setProjectTrust(engine: EngineId, workspace: WorkspaceRef, trust: ProjectTrust) {
        calls += "setProjectTrust:$trust"
    }

    override suspend fun previewImport(engine: EngineId): ImportPreview {
        calls += "previewImport"
        return ImportPreview("~/.pi/agent", emptyList())
    }

    override suspend fun importNative(
        engine: EngineId,
        selection: ImportSelection,
        expected: ConfigRevision,
    ): ImportReport {
        calls += "importNative"
        return ImportReport(selection.items.toList())
    }

    private fun project(engine: EngineId, workspace: WorkspaceRef) =
        ProjectConfig(engine, workspace, EngineConfigValues(), ProjectRevision())
}

internal class FakeWorkspaces(
    override val isAvailable: Boolean = true,
    val projects: MutableStateFlow<List<LocalWorkspace>> = MutableStateFlow(emptyList()),
) : LocalWorkspaces {
    override fun observe(): Flow<List<LocalWorkspace>> = projects

    override suspend fun register(directory: String): LocalWorkspace = error("unused")

    override suspend fun resolve(ref: WorkspaceRef): String? = null
}
