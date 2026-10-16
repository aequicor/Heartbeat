package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigProblemReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrustStatus
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlots
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineConfigurator
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFile
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFiles
import io.aequicor.heartbeat.feature.aiengine.facade.api.validateEngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Vault entries of engine configuration, bound to the slots of `configSecretUsage`. */
interface ConfigSecrets {
    /** Stores [value] as [slot] of [engine]; the caller keeps owning [value]. */
    suspend fun put(engine: EngineId, slot: String, value: Secret)

    /** Unbinds [slot] of [engine] and deletes its value. */
    suspend fun remove(engine: EngineId, slot: String)
}

/**
 * [EngineConfiguration] of the profile. Configuration lives in [EnginePreferences] next to launch settings, so the
 * management reconciler restarts idle runtimes when it changes. Saves are serialized and checked against the revision
 * the caller read; project files are read and written through the engine's [EngineConfigurator] with content
 * revisions. Logs carry engines, counts and revisions, never values, paths or secrets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineConfigurationService(
    private val registry: EngineRegistry,
    private val flags: EngineFlags,
    private val preferences: EnginePreferences,
    private val secrets: ConfigSecrets,
    private val workspaces: LocalWorkspaces,
    private val context: FacadeContext,
) : EngineConfiguration {
    private val log = Log.tag("EngineConfiguration")
    private val mutex = Mutex()

    override val state: StateFlow<EngineConfigurationState> = isOn()
        .flatMapLatest { isOn ->
            if (isOn) preferences.observe().map(::stateOf) else flowOf(EngineConfigurationState.Off)
        }
        .stateIn(context.scope, SharingStarted.Eagerly, EngineConfigurationState.Off)

    override suspend fun saveProfile(engine: EngineId, edit: ConfigEdit, expected: ConfigRevision): ConfigRevision {
        val (schema, _) = configurable(engine)
        return mutex.withLock { save(engine, schema, edit, expected) }
    }

    override suspend fun readProject(engine: EngineId, workspace: WorkspaceRef): ProjectConfig {
        val (schema, configurator) = configurable(engine)
        val directory = directoryOf(workspace)
        val files = io { configurator.readProject(directory) }
        log.i { "project configuration read engine=${engine.value} documents=${files.documents.size}" }
        return projectOf(engine, workspace, schema, files)
    }

    override suspend fun saveProject(
        engine: EngineId,
        workspace: WorkspaceRef,
        edit: ProjectEdit,
        expected: ProjectRevision,
    ): ProjectConfig = mutex.withLock {
        val (schema, configurator) = configurable(engine)
        val documents = schema.documents.filter { ConfigScope.Project in it.scopes }
        val problems = validateEngineConfig(schema, edit.values, ConfigScope.Project) + rawProblems(edit, documents)
        if (problems.any { it.isBlocking }) invalid(engine, problems)
        val directory = directoryOf(workspace)
        val current = io { configurator.readProject(directory) }
        val changes = documents.mapNotNull { spec -> changeOf(spec, edit, current.documents[spec.id]) }.toMap()
        if (changes.keys.any { current.documents[it]?.revision != expected.documents[it] }) conflict(engine)
        val written = if (changes.isEmpty()) {
            current
        } else {
            io { configurator.writeProject(directory, changes, changes.keys.associateWith { expected.documents[it] }) }
        }
        log.i { "project configuration saved engine=${engine.value} changed=${changes.size}" }
        projectOf(engine, workspace, schema, written)
    }

    override suspend fun projectTrust(engine: EngineId, workspace: WorkspaceRef): ProjectTrustStatus {
        val (schema, configurator) = configurable(engine)
        requireCapability(engine, schema, ConfigCapability.ProjectTrust)
        val trust = preferences.load().configOf(engine).trust[workspace] ?: ProjectTrust.Undecided
        val directory = directoryOf(workspace)
        val isGated = io { configurator.hasGatedConfiguration(directory) }
        log.v { "project trust engine=${engine.value} trust=$trust gated=$isGated" }
        return ProjectTrustStatus(trust, isGated)
    }

    override suspend fun setProjectTrust(engine: EngineId, workspace: WorkspaceRef, trust: ProjectTrust) {
        val (schema, _) = configurable(engine)
        requireCapability(engine, schema, ConfigCapability.ProjectTrust)
        preferences.update { chosen ->
            val config = chosen.configOf(engine)
            val decisions = if (trust == ProjectTrust.Undecided) {
                config.trust - workspace
            } else {
                config.trust + (workspace to trust)
            }
            chosen.copy(config = chosen.config + (engine to config.copy(trust = decisions)))
        }
        log.i { "project trust saved engine=${engine.value} trust=$trust" }
    }

    override suspend fun previewImport(engine: EngineId): ImportPreview {
        val (schema, configurator) = configurable(engine)
        requireCapability(engine, schema, ConfigCapability.ImportNative)
        val preview = io { configurator.previewNative() }
        log.i { "import previewed engine=${engine.value} items=${preview.items.size}" }
        return preview
    }

    override suspend fun importNative(
        engine: EngineId,
        selection: ImportSelection,
        expected: ConfigRevision,
    ): ImportReport {
        val (schema, configurator) = configurable(engine)
        requireCapability(engine, schema, ConfigCapability.ImportNative)
        return mutex.withLock {
            // Reading copies resources into the profile. Keep the revision check, copy and save serialized together.
            if (preferences.load().configOf(engine).revision != expected.value) conflict(engine)
            val native = io { configurator.readNative(selection) }
            try {
                val current = preferences.load().configOf(engine).values
                val merged = merge(current, native.values, selection.mode).copy(secrets = current.secrets)
                save(engine, schema, ConfigEdit(merged, native.secrets), expected)
                log.i {
                    "configuration imported engine=${engine.value} mode=${selection.mode} " +
                        "items=${native.imported.size} notes=${native.notes.size}"
                }
                ImportReport(native.imported, native.notes)
            } finally {
                native.secrets.values.forEach(Secret::close)
            }
        }
    }

    private fun isOn() = combine(flags.management(), flags.configuration()) { management, configuration ->
        management && configuration
    }.distinctUntilChanged()

    private fun stateOf(chosen: ProfileEnginePreferences) = EngineConfigurationState(
        isEnabled = true,
        engines = registry.all.mapNotNull { registration ->
            val schema = registration.configuration ?: return@mapNotNull null
            val id = registration.descriptor.id
            val config = chosen.configOf(id)
            EngineConfigState(
                engine = id,
                schema = schema,
                profile = config.values,
                revision = ConfigRevision(config.revision),
                problems = validateEngineConfig(schema, config.values, ConfigScope.Profile),
                trust = config.trust,
            )
        },
    )

    /** Saves under [mutex]: vault entries first, then the values, then the vault entries nothing refers to. */
    private suspend fun save(
        engine: EngineId,
        schema: EngineConfigSchema,
        edit: ConfigEdit,
        expected: ConfigRevision,
    ): ConfigRevision {
        val current = preferences.load().configOf(engine)
        if (current.revision != expected.value) conflict(engine)
        if (edit.secrets.keys.any { !SecretSlot.matches(it) }) invalid(engine, emptyList())
        val revision = current.revision + 1
        val referenced = edit.values.secretSlots()
        val written = edit.secrets.filter { (slot, value) -> value != null && slot in referenced }
        val kept = current.values.secrets.filterKeys { it in referenced && it !in edit.secrets }
        val values = edit.values.copy(secrets = kept + written.mapValues { revision })
        val problems = validateEngineConfig(schema, values, ConfigScope.Profile)
        if (problems.any { it.isBlocking }) invalid(engine, problems)
        storage("vault write engine=${engine.value}") {
            written.forEach { (slot, value) -> secrets.put(engine, slot, requireNotNull(value)) }
        }
        storage("configuration write engine=${engine.value}") {
            preferences.update { chosen ->
                val config = chosen.configOf(engine).copy(values = values, revision = revision)
                chosen.copy(config = chosen.config + (engine to config))
            }
        }
        current.values.secrets.keys.filter { it !in values.secrets }.forEach { slot -> forget(engine, slot) }
        log.i {
            "profile configuration saved engine=${engine.value} revision=$revision " +
                "documents=${values.documents.size} secrets=${values.secrets.size}"
        }
        return ConfigRevision(revision)
    }

    /** The configuration is saved already; an entry that cannot be removed stays unreferenced in the vault. */
    private suspend fun forget(engine: EngineId, slot: String) {
        try {
            secrets.remove(engine, slot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "unused configuration secret could not be removed engine=${engine.value}" }
        }
    }

    private fun projectOf(
        engine: EngineId,
        workspace: WorkspaceRef,
        schema: EngineConfigSchema,
        files: ProjectFiles,
    ): ProjectConfig {
        val documents = schema.documents.filter { ConfigScope.Project in it.scopes }
        val parsed = documents.mapNotNull { spec -> files.documents[spec.id]?.let { spec to parse(spec, it.text) } }
        val values = EngineConfigValues(
            parsed.mapNotNull { (spec, document) -> document?.let { spec.id to it } }.toMap(),
        )
        val unreadable = parsed.filter { (_, document) -> document == null }
            .associate { (spec, _) -> spec.id to files.documents.getValue(spec.id).text }
        return ProjectConfig(
            engine = engine,
            workspace = workspace,
            values = values,
            revision = ProjectRevision(documents.associate { it.id to files.documents[it.id]?.revision }),
            unreadable = unreadable,
            problems = validateEngineConfig(schema, values, ConfigScope.Project),
        )
    }

    /** What a project save writes to [spec]: raw text, the serialized value, nothing for kept unreadable files. */
    private fun changeOf(
        spec: ConfigDocumentSpec,
        edit: ProjectEdit,
        current: ProjectFile?,
    ): Pair<ConfigDocumentId, String?>? {
        // An emptied raw file is deleted: it is how a file that does not parse is removed.
        val text = when {
            spec.id in edit.raw -> edit.raw.getValue(spec.id).takeIf { it.isNotBlank() }
            spec.id in edit.values.documents -> serialize(edit.values.documents.getValue(spec.id))
            current != null && parse(spec, current.text) == null -> return null
            else -> null
        }
        return if (text == current?.text) null else spec.id to text
    }

    private fun rawProblems(edit: ProjectEdit, documents: List<ConfigDocumentSpec>): List<ConfigProblem> =
        edit.raw.mapNotNull { (id, text) ->
            val spec = documents.firstOrNull { it.id == id }
            val reason = when {
                spec == null -> ConfigProblemReason.NotInScope
                text.length > spec.maxChars -> ConfigProblemReason.TooLong
                SecretReferenceText.containsMatchIn(text) -> ConfigProblemReason.SecretInProject
                else -> null
            }
            reason?.let { ConfigProblem(id, emptyList(), it) }
        }

    private fun configurable(engine: EngineId): Pair<EngineConfigSchema, EngineConfigurator> {
        val registration = registry.find(engine)
        val schema = registration?.configuration
        val configurator = registration?.configurator
        if (!state.value.isEnabled || schema == null || configurator == null) {
            log.w { "configuration refused: off or not configurable engine=${engine.value}" }
            throw ConfigException(ConfigFailure.Unsupported)
        }
        return schema to configurator.value
    }

    private fun requireCapability(engine: EngineId, schema: EngineConfigSchema, capability: ConfigCapability) {
        if (capability in schema.capabilities) return
        log.w { "configuration refused: no $capability engine=${engine.value}" }
        throw ConfigException(ConfigFailure.Unsupported)
    }

    private suspend fun directoryOf(workspace: WorkspaceRef): String {
        val directory = workspaces.takeIf { it.isAvailable }?.let { saved ->
            storage("resolve project") { saved.resolve(workspace) }
        }
        if (directory == null) {
            // Expected for a project removed from the saved list; the caller decides how to report it.
            log.d { "project configuration refused: project unavailable" }
            throw ConfigException(ConfigFailure.ProjectUnavailable)
        }
        return directory
    }

    /** Runs adapter file work off the main thread; unexpected failures become [ConfigFailure.Storage]. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(context.io) { storage("adapter files", block) }

    private suspend fun <T> storage(operation: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConfigException) {
        log.w(e) { "$operation refused failure=${e.failure::class.simpleName.orEmpty()}" }
        throw e
    } catch (e: Exception) {
        // Adapters and the vault report platform IO errors of their own types; the panel shows one storage failure.
        // Their messages may quote paths or file contents, so only the type and stack are logged.
        log.e(e.redactedFailure()) { "$operation failed" }
        throw ConfigException(ConfigFailure.Storage)
    }

    private fun conflict(engine: EngineId): Nothing {
        log.w { "configuration changed since it was read engine=${engine.value}" }
        throw ConfigException(ConfigFailure.Conflict)
    }

    private fun invalid(engine: EngineId, problems: List<ConfigProblem>): Nothing {
        log.w { "configuration refused engine=${engine.value} problems=${problems.count { it.isBlocking }}" }
        throw ConfigException(ConfigFailure.Invalid(problems))
    }

    private companion object {
        val SecretSlot = Regex("[A-Z0-9_]{1,48}")

        /** A vault reference anywhere in raw text; project files are shared and never refer to the vault. */
        val SecretReferenceText = Regex("""\$\{HEARTBEAT_SECRET_[A-Z0-9_]{1,48}}""")
    }
}

/** Configuration values with [imported] applied: [ImportMode.Merge] merges JSON objects key by key. */
internal fun merge(current: EngineConfigValues, imported: EngineConfigValues, mode: ImportMode): EngineConfigValues =
    current.copy(
        documents = current.documents + imported.documents.mapValues { (id, document) ->
            val previous = current.documents[id]
            if (mode == ImportMode.Merge && previous is ConfigDocument.Json && document is ConfigDocument.Json) {
                ConfigDocument.Json(previous.value.mergedWith(document.value))
            } else {
                document
            }
        },
    )

private fun JsonObject.mergedWith(other: JsonObject): JsonObject = JsonObject(
    this + other.mapValues { (key, value) ->
        val previous = this[key]
        if (previous is JsonObject && value is JsonObject) previous.mergedWith(value) else value
    },
)

/** A project file as configuration; null when a JSON document does not parse to an object. */
private fun parse(spec: ConfigDocumentSpec, text: String): ConfigDocument? = when (spec.format) {
    ConfigFormat.Text -> ConfigDocument.Text(text)

    ConfigFormat.Json -> try {
        (ProjectJson.parseToJsonElement(text) as? JsonObject)?.let(ConfigDocument::Json)
    } catch (e: SerializationException) {
        // A project file with comments or a syntax error is kept and edited as text; its content is not logged.
        Log.tag("EngineConfiguration").w(e.redactedFailure()) { "project document is not plain JSON" }
        null
    }
}

private fun serialize(document: ConfigDocument): String = when (document) {
    is ConfigDocument.Text -> document.value
    is ConfigDocument.Json -> ProjectJson.encodeToString(JsonObject.serializer(), document.value) + "\n"
}

private val ProjectJson = Json { prettyPrint = true }
