package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigSection
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalizedText
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Fixed adapter-owned project directory; metadata never supplies directories or insertion syntax. */
public data class AssistDirectory(
    val path: String,
    val kind: EngineAssist.Kind,
    val isSkill: Boolean,
    val prefix: String,
    val isLiteralOnly: Boolean = false,
    /** Native skills require explicit literal name and description; imported commands may use directory names. */
    val isNativeSkillMetadataRequired: Boolean = false,
)

/**
 * Bounded, cancellable project metadata reader for native adapters. Reads only after an explicit ProjectTrust
 * decision for the engine/workspace, never executes frontmatter or starts a process, and stays inside the root.
 * Its configuration surface stores trust only; it neither edits nor imports native configuration files.
 */
public interface ProjectAssistReader : EngineConfigurator {
    /** Returns selected project metadata; unsupported platforms and untrusted projects contribute nothing. */
    public suspend fun read(
        engine: EngineId,
        workspace: WorkspaceRef?,
        directories: List<AssistDirectory>,
    ): List<EngineAssist>

    override suspend fun readProject(directory: String): ProjectFiles = ProjectFiles()

    override suspend fun writeProject(
        directory: String,
        changes: Map<ConfigDocumentId, String?>,
        expected: Map<ConfigDocumentId, String?>,
    ): ProjectFiles = throw ConfigException(ConfigFailure.Unsupported)

    override suspend fun hasGatedConfiguration(directory: String): Boolean = true

    /** Portable fallback and declarative trust-only panel. */
    public companion object {
        /** No filesystem discovery on platforms without a native reader. */
        public val None: ProjectAssistReader = object : ProjectAssistReader {
            override suspend fun read(
                engine: EngineId,
                workspace: WorkspaceRef?,
                directories: List<AssistDirectory>,
            ): List<EngineAssist> = emptyList()
        }

        /** Trust controls discovery for suggestions; it grants no tool-execution permission. */
        public val Schema: EngineConfigSchema = EngineConfigSchema(
            documents = emptyList(),
            pages = listOf(
                ConfigPage(
                    "suggestions",
                    LocalizedText("Composer suggestions", mapOf("ru" to "Подсказки композера")),
                    listOf(
                        ConfigSection(
                            "project-assists",
                            LocalizedText("Project commands and skills", mapOf("ru" to "Команды и навыки проекта")),
                            emptyList(),
                            note = LocalizedText(
                                "Trust a project in Projects to show its commands and skills. " +
                                    "Tool permissions stay separate.",
                                mapOf(
                                    "ru" to
                                        "Доверьте проект во вкладке «Проекты», чтобы видеть его команды и навыки. " +
                                        "Разрешения инструментов настраиваются отдельно.",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            capabilities = setOf(ConfigCapability.ProjectTrust),
        )
    }
}
