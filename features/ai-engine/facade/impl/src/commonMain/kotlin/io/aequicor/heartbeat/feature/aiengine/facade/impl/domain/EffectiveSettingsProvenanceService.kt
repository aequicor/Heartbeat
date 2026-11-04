package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigField
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFieldKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveResolution
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveSettingsKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveSettingsProvenance
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveSettingsRead
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveSettingsReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.EffectiveUnknownReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LayerProvenance
import io.aequicor.heartbeat.feature.aiengine.facade.api.MASKED_SETTING_VALUE
import io.aequicor.heartbeat.feature.aiengine.facade.api.ObservedValue
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.RouteKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsLayer
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsProvenance
import io.aequicor.heartbeat.feature.aiengine.facade.api.SettingsSourceKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SurfaceSettingsReport
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnreadableReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.isSecretKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.read
import io.aequicor.heartbeat.feature.aiengine.facade.api.secretSlotOf
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EffectiveSettingsRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineConfigurator
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineDeclaredResolution
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineEffectiveSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * [EffectiveSettingsProvenance] over the profile's cached configuration, its saved projects and the adapter's
 * declared engine layer. Reading [reports] and [report] is a pure lookup; [read] is the only operation that reads
 * the profile, a trusted project or the adapter.
 *
 * One read is bounded and revalidates the exact binding, source revision, profile revision and project trust
 * decision before and after those reads, so a route, source, configuration or trust change discards the result
 * instead of publishing it under another identity. A project is read only after its trust decision allows loading
 * executable configuration; revoking that decision retires a report that already published project values. No
 * layer's value is inferred: the effective value exists only when the adapter declares it. Logs carry engines,
 * counts and revisions, never values, paths or secrets.
 */
internal class EffectiveSettingsProvenanceService(
    private val configuration: EngineConfiguration,
    private val registry: EngineRegistry,
    private val bindings: StateFlow<List<EngineBinding>>,
    private val sources: StateFlow<List<AuthSource>>,
    private val context: FacadeContext,
) : EffectiveSettingsProvenance {
    private val log = Log.tag("EffectiveSettings")
    private val published = MutableStateFlow<Map<EffectiveSettingsKey, EffectiveSettingsReport>>(emptyMap())

    override val reports: StateFlow<Map<EffectiveSettingsKey, EffectiveSettingsReport>> = published.asStateFlow()

    /** The cached report while its binding, source revision, profile revision and trust are current; no IO. */
    override fun report(route: RouteKey, workspace: WorkspaceRef?): EffectiveSettingsReport? {
        val key = EffectiveSettingsKey(route, workspace)
        val report = published.value[key] ?: return null
        return report.takeIf { isCurrent(inputs(), key, it) }
    }

    init {
        context.scope.launch {
            combine(bindings, sources, configuration.state) { saved, auth, config ->
                Inputs(saved, auth, config)
            }.collect(::retireStale)
        }
    }

    override suspend fun read(route: RouteKey, workspace: WorkspaceRef?): EffectiveSettingsRead {
        val inputs = inputs()
        val before = inputs.stamp(route) ?: fail(OperationNotAllowed)
        val state = inputs.config
        val engine = state.engine(route.engine)
        if (!state.isEnabled || engine == null) throw ConfigException(ConfigFailure.Unsupported)
        val configurator = registry.find(route.engine)?.configurator?.value
            ?: throw ConfigException(ConfigFailure.Unsupported)
        val surfaces = surfacesOf(engine.schema)
        val trust = observedTrust(engine, workspace)
        val project = readProject(route, workspace, engine, surfaces)
        val report = readEngine(configurator, engine, project, surfaces)
        currentCoroutineContext().ensureActive()
        val after = inputs()
        val afterEngine = after.config.engine(route.engine)
        val isChanged = after.stamp(route) != before || afterEngine?.revision != engine.revision ||
            observedTrust(afterEngine, workspace) != trust
        if (isChanged) {
            log.i {
                "effective settings discarded: route, source, profile or trust changed engine=${route.engine.value}"
            }
            return EffectiveSettingsRead.Discarded
        }
        val observed = reportOf(route, workspace, engine, before, trust, project, report, surfaces)
        log.i {
            "effective settings observed engine=${route.engine.value} surfaces=${surfaces.size} " +
                "project=${project is ProjectInput.Loaded} layers=" +
                observed.surfaces.count { it.effective is EffectiveResolution.Declared }
        }
        published.value = published.value + (EffectiveSettingsKey(route, workspace) to observed)
        return EffectiveSettingsRead.Observed(observed)
    }

    /** Retires reports whose binding, source, source revision, profile revision or trust changed; no IO. */
    private fun retireStale(inputs: Inputs) {
        val current = published.value
        if (current.isEmpty()) return
        val next = current.filter { (key, report) -> isCurrent(inputs, key, report) }
        if (next.size != current.size) {
            log.d { "effective settings reports retired count=${current.size - next.size}" }
            published.value = next
        }
    }

    private fun inputs(): Inputs = Inputs(bindings.value, sources.value, configuration.state.value)

    /** Whether [report] still belongs to the exact route, source revision, profile revision and trust in [inputs]. */
    private fun isCurrent(inputs: Inputs, key: EffectiveSettingsKey, report: EffectiveSettingsReport): Boolean {
        val engine = inputs.config.engine(key.route.engine)
        return inputs.config.isEnabled && engine != null &&
            inputs.stamp(key.route) == RouteStamp(report.source, report.sourceRevision) &&
            engine.revision == report.profileRevision &&
            observedTrust(engine, key.workspace) == report.projectTrust
    }

    /**
     * The trust decision a report of [workspace] was observed under; null when no project is named or the engine
     * does not gate project configuration on trust. Revoking or granting trust therefore makes a report stale.
     */
    private fun observedTrust(engine: EngineConfigState?, workspace: WorkspaceRef?): ProjectTrust? {
        if (workspace == null || engine == null) return null
        if (ConfigCapability.ProjectTrust !in engine.schema.capabilities) return null
        return engine.trust[workspace] ?: ProjectTrust.Undecided
    }

    /**
     * Reads the project layer once for the whole report: only for a saved trusted project whose engine can read
     * projects and whose schema has project surfaces. Everything else is typed without touching project files.
     */
    private suspend fun readProject(
        route: RouteKey,
        workspace: WorkspaceRef?,
        engine: EngineConfigState,
        surfaces: List<ConfigField>,
    ): ProjectInput {
        if (workspace == null || surfaces.none { ConfigScope.Project in it.scopes }) return ProjectInput.None
        val trust = engine.trust[workspace] ?: ProjectTrust.Undecided
        val isGated = ConfigCapability.ProjectTrust in engine.schema.capabilities
        if (isGated && trust != ProjectTrust.Trusted) return ProjectInput.NeedsTrust
        return try {
            val project = withTimeoutOrNull(SettingsReadBound) {
                withContext(context.io) { configuration.readProject(route.engine, workspace) }
            }
            if (project == null) ProjectInput.Unreadable(UnreadableReason.TimedOut) else ProjectInput.Loaded(project)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConfigException) {
            log.w(e.redactedFailure()) {
                "project settings layer refused engine=${route.engine.value} failure=${e.failure::class.simpleName}"
            }
            projectFailure(e.failure)
        } catch (e: Exception) {
            log.w(e.redactedFailure()) { "project settings layer failed engine=${route.engine.value}" }
            ProjectInput.Unreadable(UnreadableReason.FilesUnreadable)
        }
    }

    /** Asks the adapter once, bounded; a missing declaration never becomes an inferred engine value. */
    private suspend fun readEngine(
        configurator: EngineConfigurator,
        engine: EngineConfigState,
        project: ProjectInput,
        surfaces: List<ConfigField>,
    ): EngineReport {
        if (surfaces.isEmpty()) return EngineReport.NotDeclared
        val request = EffectiveSettingsRequest(
            engine.profile,
            (project as? ProjectInput.Loaded)?.config?.values,
        )
        return try {
            withTimeoutOrNull(SettingsReadBound) {
                withContext(context.io) {
                    configurator.effectiveSettings(request)?.let(EngineReport::Declared) ?: EngineReport.NotDeclared
                }
            } ?: EngineReport.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConfigException) {
            log.w(e.redactedFailure()) { "engine settings layer refused engine=${engine.engine.value}" }
            EngineReport.Failed
        } catch (e: Exception) {
            log.w(e.redactedFailure()) { "engine settings layer failed engine=${engine.engine.value}" }
            EngineReport.Failed
        }
    }

    private fun reportOf(
        route: RouteKey,
        workspace: WorkspaceRef?,
        engine: EngineConfigState,
        stamp: RouteStamp,
        trust: ProjectTrust?,
        project: ProjectInput,
        report: EngineReport,
        surfaces: List<ConfigField>,
    ): EffectiveSettingsReport = EffectiveSettingsReport(
        key = EffectiveSettingsKey(route, workspace),
        source = stamp.source,
        sourceRevision = stamp.revision,
        profileRevision = engine.revision,
        projectRevision = (project as? ProjectInput.Loaded)?.config?.revision,
        projectTrust = trust,
        surfaces = surfaces.map { field ->
            val heartbeat = heartbeatOf(engine, field)
            val engineLayer = engineOf(engine.schema, field, report)
            val projectLayer = projectOf(field, project)
            SurfaceSettingsReport(
                field,
                heartbeat,
                engineLayer,
                projectLayer,
                resolutionOf(field, report, heartbeat, engineLayer, projectLayer),
            )
        },
    )

    private fun heartbeatOf(engine: EngineConfigState, field: ConfigField): LayerProvenance {
        val profile = if (ConfigScope.Profile in field.scopes) engine.profile.read(field.binding) else null
        if (profile != null) {
            return heartbeatLayer(
                SettingsProvenance.Observed(observed(field, profile), SettingsSourceKind.HeartbeatProfile),
            )
        }
        val default = if (ConfigScope.Profile in field.scopes) defaultOf(field.kind) else null
        val provenance = default?.let {
            SettingsProvenance.Observed(observed(field, it), SettingsSourceKind.HeartbeatDefault)
        } ?: SettingsProvenance.NotApplicable
        return heartbeatLayer(provenance)
    }

    private fun engineOf(schema: EngineConfigSchema, field: ConfigField, report: EngineReport): LayerProvenance {
        if (schema.document(field.binding.document)?.isHostOnly == true) {
            return engineLayer(SettingsProvenance.NotApplicable)
        }
        val provenance = when (report) {
            EngineReport.NotDeclared -> SettingsProvenance.Unavailable(UnavailableReason.AdapterDoesNotReport)

            EngineReport.TimedOut -> SettingsProvenance.Unavailable(UnavailableReason.AdapterTimedOut)

            EngineReport.Failed -> SettingsProvenance.Unavailable(UnavailableReason.AdapterFailed)

            is EngineReport.Declared -> report.settings.values[field.id]?.let { value ->
                SettingsProvenance.Observed(observed(field, value), SettingsSourceKind.EngineEffective)
            } ?: SettingsProvenance.Unavailable(UnavailableReason.SurfaceNotReported)
        }
        return engineLayer(provenance)
    }

    private fun projectOf(field: ConfigField, project: ProjectInput): LayerProvenance = when (project) {
        ProjectInput.None -> projectLayer(SettingsProvenance.NotApplicable)
        ProjectInput.NeedsTrust -> projectLayer(SettingsProvenance.NeedsTrust)
        is ProjectInput.Unavailable -> projectLayer(SettingsProvenance.Unavailable(project.reason))
        is ProjectInput.Unreadable -> projectLayer(SettingsProvenance.Unreadable(project.reason))
        is ProjectInput.Loaded -> loadedProjectOf(field, project.config)
    }

    private fun loadedProjectOf(field: ConfigField, project: ProjectConfig): LayerProvenance {
        if (ConfigScope.Project !in field.scopes) return projectLayer(SettingsProvenance.NotApplicable)
        val document = field.binding.document
        val isInvalid = document in project.unreadable ||
            project.problems.any { it.isBlocking && it.document == document && it.field == field.id }
        if (isInvalid) return projectLayer(SettingsProvenance.Unreadable(UnreadableReason.InvalidDocument))
        val value = project.values.read(field.binding) ?: return projectLayer(SettingsProvenance.NotApplicable)
        return projectLayer(SettingsProvenance.Observed(observed(field, value), SettingsSourceKind.ProjectFile))
    }

    /** The effective value exists only when the adapter itself declares it for observed layers. */
    private fun resolutionOf(
        field: ConfigField,
        report: EngineReport,
        heartbeat: LayerProvenance,
        engine: LayerProvenance,
        project: LayerProvenance,
    ): EffectiveResolution {
        val declared = (report as? EngineReport.Declared)?.settings
        val resolution = declared?.merged?.get(field.id)
        val reason = resolutionProblem(field, declared, resolution, heartbeat, engine, project)
        if (reason != null || resolution == null) return unknown(reason ?: SurfaceNotReported)
        return EffectiveResolution.Declared(observed(field, resolution.value), resolution.layers)
    }

    /** Why a declaration does not describe this surface; null when it does. */
    private fun resolutionProblem(
        field: ConfigField,
        declared: EngineEffectiveSettings?,
        resolution: EngineDeclaredResolution?,
        heartbeat: LayerProvenance,
        engine: LayerProvenance,
        project: LayerProvenance,
    ): EffectiveUnknownReason? = when {
        declared == null -> EngineLayerNotObserved
        resolution == null -> SurfaceNotReported
        SettingsLayer.Engine !in resolution.layers || engine.isNotObserved -> EngineLayerNotObserved
        SettingsLayer.Project in resolution.layers && ConfigScope.Project !in field.scopes -> DeclarationIncomplete
        SettingsLayer.Project in resolution.layers && project.isNotObserved -> ProjectLayerNotObserved
        SettingsLayer.Heartbeat in resolution.layers && heartbeat.isNotObserved -> HeartbeatLayerNotObserved
        else -> null
    }

    private fun unknown(reason: EffectiveUnknownReason): EffectiveResolution = EffectiveResolution.Unknown(reason)

    private fun projectFailure(failure: ConfigFailure): ProjectInput = when (failure) {
        ConfigFailure.ProjectUnavailable -> ProjectInput.Unavailable(UnavailableReason.ProjectNotSaved)

        ConfigFailure.Unsupported, ConfigFailure.NativeMissing ->
            ProjectInput.Unavailable(UnavailableReason.ProjectNotSupported)

        ConfigFailure.Storage, ConfigFailure.Conflict -> ProjectInput.Unreadable(UnreadableReason.FilesUnreadable)

        is ConfigFailure.Invalid -> ProjectInput.Unreadable(UnreadableReason.InvalidDocument)
    }

    private fun surfacesOf(schema: EngineConfigSchema): List<ConfigField> =
        schema.pages.flatMap { page -> page.sections.flatMap { it.fields } }
            .filter { it.kind !is ConfigFieldKind.ManagedNote }
            .distinctBy { it.id }

    private fun heartbeatLayer(provenance: SettingsProvenance): LayerProvenance =
        LayerProvenance(SettingsLayer.Heartbeat, provenance)

    private fun engineLayer(provenance: SettingsProvenance): LayerProvenance =
        LayerProvenance(SettingsLayer.Engine, provenance)

    private fun projectLayer(provenance: SettingsProvenance): LayerProvenance =
        LayerProvenance(SettingsLayer.Project, provenance)

    private data class RouteStamp(val source: AuthSourceId, val revision: AuthRevision)

    private class Inputs(
        val bindings: List<EngineBinding>,
        val sources: List<AuthSource>,
        val config: EngineConfigurationState,
    ) {
        /** The exact route's binding and source; null when it is gone, disabled or has no source. */
        fun stamp(route: RouteKey): RouteStamp? {
            val binding = bindings.firstOrNull { it.id == route.binding && it.engine == route.engine } ?: return null
            if (!binding.isEnabled) return null
            val source = sources.firstOrNull { it.info.id == binding.authSource } ?: return null
            return RouteStamp(source.info.id, source.info.revision)
        }
    }

    private sealed interface ProjectInput {
        data object None : ProjectInput

        data object NeedsTrust : ProjectInput

        data class Unavailable(val reason: UnavailableReason) : ProjectInput

        data class Unreadable(val reason: UnreadableReason) : ProjectInput

        data class Loaded(val config: ProjectConfig) : ProjectInput
    }

    private sealed interface EngineReport {
        data object NotDeclared : EngineReport

        data object TimedOut : EngineReport

        data object Failed : EngineReport

        data class Declared(val settings: EngineEffectiveSettings) : EngineReport
    }

    private companion object {
        val SettingsReadBound = 10.seconds

        val EngineLayerNotObserved = EffectiveUnknownReason.EngineLayerNotObserved
        val SurfaceNotReported = EffectiveUnknownReason.SurfaceNotReported
        val DeclarationIncomplete = EffectiveUnknownReason.DeclarationIncomplete
        val HeartbeatLayerNotObserved = EffectiveUnknownReason.HeartbeatLayerNotObserved
        val ProjectLayerNotObserved = EffectiveUnknownReason.ProjectLayerNotObserved
    }
}

private val LayerProvenance.isNotObserved: Boolean
    get() = provenance !is SettingsProvenance.Observed

/** The default the documented surface declares, or null; never a value Heartbeat writes by itself. */
private fun defaultOf(kind: ConfigFieldKind): JsonElement? = when (kind) {
    is ConfigFieldKind.Flag -> kind.isOnByDefault?.let { JsonPrimitive(it) }

    is ConfigFieldKind.Choice -> kind.default

    is ConfigFieldKind.Number -> kind.default?.let { JsonPrimitive(it) }

    is ConfigFieldKind.Text -> kind.default?.let { JsonPrimitive(it) }

    is ConfigFieldKind.TextList,
    is ConfigFieldKind.KeyValues,
    is ConfigFieldKind.Entries,
    is ConfigFieldKind.Document,
    is ConfigFieldKind.ManagedNote,
    -> null
}

/** An observed JSON value with the surface's own secret type and every nested secret entry masked. */
private fun observed(field: ConfigField, value: JsonElement): ObservedValue {
    val path = (field.binding as? ConfigBinding.JsonPath)?.path.orEmpty()
    val primitive = value as? JsonPrimitive
    if (primitive != null && isSecretText(path, primitive)) return ObservedValue.Secret
    return ObservedValue.Literal(masked(value))
}

/** Whether a string at [path] is a vault reference or a plain value of a secret-named setting. */
private fun isSecretText(path: List<String>, primitive: JsonPrimitive): Boolean {
    if (!primitive.isString) return false
    return (path.isNotEmpty() && isSecretKey(path.last())) || secretSlotOf(primitive.content) != null
}

/** An adapter-declared value sanitised with the same rule as a value Heartbeat read itself. */
private fun observed(field: ConfigField, value: ObservedValue): ObservedValue = when (value) {
    is ObservedValue.Literal -> observed(field, value.value)
    ObservedValue.Secret -> ObservedValue.Secret
}

/** Masks every secret reference and every entry of a secret-named object key; contents are never logged. */
private fun masked(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            if (isSecretKey(key)) JsonPrimitive(MASKED_SETTING_VALUE) else masked(value)
        },
    )

    is JsonArray -> JsonArray(element.map(::masked))

    is JsonPrimitive -> if (element.isString && secretSlotOf(element.content) != null) {
        JsonPrimitive(MASKED_SETTING_VALUE)
    } else {
        element
    }
}
