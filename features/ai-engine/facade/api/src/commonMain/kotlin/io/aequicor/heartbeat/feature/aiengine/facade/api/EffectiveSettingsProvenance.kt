package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Provenance of the effective settings of one exact engine/binding route and an optional saved project.
 *
 * A report lists the settings surfaces the engine's [EngineConfigSchema] documents and, for each of them, what
 * Heartbeat observed in each layer: the Heartbeat layer (the profile value written at launch or the declared
 * default), the engine layer as the adapter declares it and the saved project layer. A layer is never given a
 * value it did not observe, a project is never read before its trust decision allows it, and Heartbeat does not
 * combine the layers itself: [EffectiveResolution] carries a value only when the connector declares that
 * resolution and stays [EffectiveResolution.Unknown] otherwise.
 *
 * [reports] and [report] are pure lookups over the last published observations and perform no IO. [read] is the
 * only operation that reads the profile, the project and the adapter; it is cancellable and bounded, and it
 * revalidates the exact route and source revision before and after those reads.
 */
public interface EffectiveSettingsProvenance {
    /**
     * Published reports by exact route and workspace. A report is retired when its binding, source revision or
     * profile revision no longer matches, so nothing here is presented as current after its inputs changed.
     */
    public val reports: StateFlow<Map<EffectiveSettingsKey, EffectiveSettingsReport>>

    /** The last published report of [route] and [workspace], or null; performs no IO. */
    public fun report(route: RouteKey, workspace: WorkspaceRef? = null): EffectiveSettingsReport? =
        reports.value[EffectiveSettingsKey(route, workspace)]

    /**
     * Observes the layers of [route] and [workspace] and publishes the report.
     *
     * Only this call reads the profile configuration, the trusted project files and the adapter. It suspends only
     * for that work, is bounded and revalidates the binding and source revision before and after the reads; a
     * change discards the result without publishing it. Cancellation propagates.
     *
     * Throws [EngineException] when the route has no enabled saved binding or its source disappeared, and
     * [ConfigException] when engine configuration is off or the engine declares no configuration schema.
     */
    public suspend fun read(route: RouteKey, workspace: WorkspaceRef? = null): EffectiveSettingsRead
}

/** Last published observation of one exact route and workspace. */
public data class EffectiveSettingsKey(val route: RouteKey, val workspace: WorkspaceRef? = null)

/** Outcome of an explicit [EffectiveSettingsProvenance.read]. */
public sealed interface EffectiveSettingsRead {
    /** The report was observed for the exact route and source revision and is now published. */
    public data class Observed(val report: EffectiveSettingsReport) : EffectiveSettingsRead

    /** The binding, source or profile revision changed while reading; nothing was published. */
    public data object Discarded : EffectiveSettingsRead
}

/**
 * Independently observed layers of one exact route and workspace. The report keeps the layers apart; it is not a
 * resolved configuration.
 */
public data class EffectiveSettingsReport(
    val key: EffectiveSettingsKey,
    /** Credential source whose revision was observed before and after the read. */
    val source: AuthSourceId,
    /** Revision of [source] at the read. */
    val sourceRevision: AuthRevision,
    /** Profile configuration revision whose values and defaults the Heartbeat layer reports. */
    val profileRevision: ConfigRevision,
    /** Content revision of the project layer; null when the project was not read. */
    val projectRevision: ProjectRevision? = null,
    /** One entry per documented [ConfigField] of the engine's schema, in page order. */
    val surfaces: List<SurfaceSettingsReport>,
) {
    /** The report of the documented surface [id], or null. */
    public fun surface(id: String): SurfaceSettingsReport? = surfaces.firstOrNull { it.surface.id == id }

    override fun toString(): String = "EffectiveSettingsReport(surfaces=${surfaces.size})"
}

/** The three layers Heartbeat reports separately and never merges by itself. */
public enum class SettingsLayer {
    /** What Heartbeat itself hands to the engine: the saved profile value or the declared default. */
    Heartbeat,

    /** The engine's own effective configuration as its adapter declares it. */
    Engine,

    /** Configuration files inside the saved project. */
    Project,
}

/** One documented settings surface with its three layer observations and the declared resolution, if any. */
public data class SurfaceSettingsReport(
    val surface: ConfigField,
    val heartbeat: LayerProvenance,
    val engine: LayerProvenance,
    val project: LayerProvenance,
    val effective: EffectiveResolution,
) {
    init {
        require(heartbeat.layer == SettingsLayer.Heartbeat) { "Heartbeat observation of a foreign layer" }
        require(engine.layer == SettingsLayer.Engine) { "Engine observation of a foreign layer" }
        require(project.layer == SettingsLayer.Project) { "Project observation of a foreign layer" }
    }
}

/** One layer's typed provenance; [provenance] is never a value the layer did not observe. */
public data class LayerProvenance(val layer: SettingsLayer, val provenance: SettingsProvenance) {
    init {
        if (provenance is SettingsProvenance.Observed) {
            require(provenance.source.layer == layer) { "Source kind of a foreign layer" }
        }
        require(provenance !is SettingsProvenance.NeedsTrust || layer == SettingsLayer.Project) {
            "Only a project layer can need trust"
        }
    }
}

/** Typed observation of one layer for one settings surface. */
public sealed interface SettingsProvenance {
    /** The layer's value was observed; [source] says what exactly was observed. */
    public data class Observed(val value: ObservedValue, val source: SettingsSourceKind) : SettingsProvenance

    /** The layer has data but it cannot be used as configuration. */
    public data class Unreadable(val reason: UnreadableReason) : SettingsProvenance

    /** The layer cannot be observed at all. */
    public data class Unavailable(val reason: UnavailableReason) : SettingsProvenance

    /** The layer has no say for this surface: another scope, an absent file or a host-only value. */
    public data object NotApplicable : SettingsProvenance

    /** The project has configuration for this surface but its trust decision does not load it. */
    public data object NeedsTrust : SettingsProvenance
}

/** What an [SettingsProvenance.Observed] value came from; every kind belongs to exactly one [SettingsLayer]. */
public enum class SettingsSourceKind(public val layer: SettingsLayer) {
    /**
     * The default the documented surface declares and Heartbeat assumes while the profile stores no value. It is
     * not a claim about how the engine itself resolves a missing value.
     */
    HeartbeatDefault(SettingsLayer.Heartbeat),

    /** The value saved in the profile configuration, which Heartbeat writes into the engine directory at launch. */
    HeartbeatProfile(SettingsLayer.Heartbeat),

    /** The engine's own effective configuration as the adapter declared it. */
    EngineEffective(SettingsLayer.Engine),

    /** The value read from the saved project document after the project was trusted. */
    ProjectFile(SettingsLayer.Project),
}

/** A layer's observed value. Secret material is never included. */
public sealed interface ObservedValue {
    /** A JSON value with every secret reference or secret-named entry already replaced by [MASKED_SETTING_VALUE]. */
    public data class Literal(val value: JsonElement) : ObservedValue {
        override fun toString(): String = "ObservedValue.Literal"
    }

    /** The surface holds a secret type; Heartbeat observed the type, never the content. */
    public data object Secret : ObservedValue
}

/** Marker written instead of a secret value or a secret-named entry of an [ObservedValue.Literal]. */
public const val MASKED_SETTING_VALUE: String = "***"

/** Why a layer's data cannot be used; never contains contents or paths. */
public enum class UnreadableReason {
    /** A project document exists but does not parse or is invalid as configuration. */
    InvalidDocument,

    /** The project files could not be read. */
    FilesUnreadable,

    /** Reading the project exceeded the bound of one read. */
    TimedOut,
}

/** Why a layer cannot be observed; never contains contents or paths. */
public enum class UnavailableReason {
    /** The adapter does not declare the engine's effective configuration. */
    AdapterDoesNotReport,

    /** The adapter reported other surfaces but not this one. */
    SurfaceNotReported,

    /** The adapter's report failed. */
    AdapterFailed,

    /** The adapter's report exceeded the bound of one read. */
    AdapterTimedOut,

    /** The saved project is not on disk any more. */
    ProjectNotSaved,

    /** The engine does not read project settings on this host. */
    ProjectNotSupported,
}

/** The value that applies after the layers; reported only when the connector itself declares it. */
public sealed interface EffectiveResolution {
    /** The adapter declared this resolution for the named observed layers. */
    public data class Declared(val value: ObservedValue, val layers: Set<SettingsLayer>) : EffectiveResolution {
        init {
            require(layers.isNotEmpty()) { "A declared resolution names its layers" }
        }
    }

    /** Heartbeat does not know the connector's resolution and never invents one. */
    public data class Unknown(val reason: EffectiveUnknownReason) : EffectiveResolution
}

/** Why the effective value stays unknown. */
public enum class EffectiveUnknownReason {
    /** The adapter does not report its engine layer, so no resolution is grounded. */
    EngineLayerNotObserved,

    /** The adapter reported the engine layer but declares no resolution for this surface. */
    SurfaceNotReported,

    /** The declaration omits the engine layer or names a layer this surface cannot have. */
    DeclarationIncomplete,

    /** The declaration accounts for the Heartbeat layer, which this surface did not observe. */
    HeartbeatLayerNotObserved,

    /** The declaration accounts for the project layer, which was not read. */
    ProjectLayerNotObserved,
}

/** Provenance of hosts without the facade implementation: nothing is ever observed. */
public object UnavailableEffectiveSettingsProvenance : EffectiveSettingsProvenance {
    private val none = MutableStateFlow<Map<EffectiveSettingsKey, EffectiveSettingsReport>>(emptyMap())

    override val reports: StateFlow<Map<EffectiveSettingsKey, EffectiveSettingsReport>> = none.asStateFlow()

    override suspend fun read(route: RouteKey, workspace: WorkspaceRef?): EffectiveSettingsRead =
        throw ConfigException(ConfigFailure.Unsupported)
}
