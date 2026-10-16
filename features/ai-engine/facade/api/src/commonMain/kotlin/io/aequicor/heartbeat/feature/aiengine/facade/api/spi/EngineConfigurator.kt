package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.core.secrets.SecretUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNote
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection

/**
 * File side of an engine's configuration, called only by the facade's configuration service. The facade resolves
 * projects to directories, parses and validates documents and owns the profile storage; the adapter maps document
 * ids to its files. Paths and contents are never logged. Failures are [ConfigException].
 */
public interface EngineConfigurator {
    /** Project documents under [directory] as raw text with content revisions; absent documents are missing. */
    public suspend fun readProject(directory: String): ProjectFiles

    /**
     * Writes [changes] under [directory] (null deletes a document) atomically per file, when every changed document
     * still has its [expected] revision (null: absent); otherwise throws [ConfigFailure.Conflict].
     */
    public suspend fun writeProject(
        directory: String,
        changes: Map<ConfigDocumentId, String?>,
        expected: Map<ConfigDocumentId, String?>,
    ): ProjectFiles

    /** Whether the project in [directory] has configuration that loads only when the project is trusted. */
    public suspend fun hasGatedConfiguration(directory: String): Boolean = false

    /** What an import from the engine's own user configuration would bring. */
    public suspend fun previewNative(): ImportPreview = unsupported()

    /** Reads [selection] from the engine's own user configuration; plain credentials come back as vault secrets. */
    public suspend fun readNative(selection: ImportSelection): NativeImport = unsupported()
}

/** Raw project documents by id. */
public data class ProjectFiles(val documents: Map<ConfigDocumentId, ProjectFile> = emptyMap())

/** One project document: its text and a content revision (a hash) for conflict checks. */
public data class ProjectFile(val text: String, val revision: String) {
    override fun toString(): String = "ProjectFile(chars=${text.length})"
}

/**
 * Values read from the engine's own configuration, with [secrets] by vault slot for the references the values
 * contain. The receiver closes the secrets.
 */
public data class NativeImport(
    val values: EngineConfigValues,
    val secrets: Map<String, Secret> = emptyMap(),
    val imported: List<String> = emptyList(),
    val notes: List<ImportNote> = emptyList(),
) {
    override fun toString(): String = "NativeImport(values=$values, secrets=${secrets.size})"
}

/** Vault slot of the configuration secret [slot] of [engine]; adapters resolve references through it at launch. */
public fun configSecretUsage(engine: EngineId, slot: String): SecretUsage =
    SecretUsage("engine_config", engine.value.replace(InvalidUsage, "_"), slot)

private val InvalidUsage = Regex("[^a-zA-Z0-9_-]")

private fun unsupported(): Nothing = throw ConfigException(ConfigFailure.Unsupported)
