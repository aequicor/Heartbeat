package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthCheck
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Heartbeat platforms supported by an engine registration. */
@Serializable
public enum class EnginePlatform { Android, Ios, DesktopMacOs, DesktopWindows }

/** Engine family is descriptive metadata, never a substitute for capabilities. */
@Serializable
public enum class EngineFamily { BuiltIn, Vendor, MultiProvider, Acp }

/** Installation requirement with a non-executable hint; UI never launches arbitrary hint text. */
@Serializable
public data class EngineRequirement(val id: String, val description: String, val minimumVersion: String? = null)

/** Static metadata; reading a descriptor must not launch a process or perform IO. */
public data class EngineDescriptor(
    val id: EngineId,
    val title: String,
    val family: EngineFamily,
    val platforms: Set<EnginePlatform>,
    val toggle: FeatureToggle.Flag,
    val requirements: List<EngineRequirement> = emptyList(),
    val declaredFeatures: Set<EngineFeatureId> = emptySet(),
    /** Preferred initial engine on supported platforms; never overrides an explicit saved route. */
    val isDefault: Boolean = false,
)

/** Availability of the installation, independent of whether a credential is configured. */
@Serializable
public sealed interface EngineAvailability {
    /** No explicit probe has completed. */
    @Serializable
    public data object Unknown : EngineAvailability

    /** Installation requirements are met. */
    @Serializable
    public data object Available : EngineAvailability

    /** This platform is not supported. */
    @Serializable
    public data object UnsupportedPlatform : EngineAvailability

    /** A probe established a concrete failure. */
    @Serializable
    public data class Unavailable(val failure: EngineFailure) : EngineAvailability
}

/** Cached observation metadata. Reading a cache does not itself trigger a check. */
@Serializable
public data class Observation(val checkedAt: Instant? = null, val isStale: Boolean = true)

/** One profile-owned binding. Lower priority numbers win only during explicit initial route resolution. */
@Serializable
public data class EngineBinding(
    val id: EngineBindingId,
    val engine: EngineId,
    val authSource: AuthSourceId,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
)

/** Catalog entry; connection and installation readiness are independent. */
public data class EngineInfo(
    val descriptor: EngineDescriptor,
    val availability: EngineAvailability,
    val bindings: List<EngineBinding>,
    val observation: Observation = Observation(),
)

/** A model is resolved only within this exact engine and binding. No implicit credential fallback is permitted. */
@Serializable
public data class EngineTarget(val engine: EngineId, val binding: EngineBindingId, val model: ModelId)

/** Effective model information for one credential route. */
@Serializable
public data class ModelInfo(
    val target: EngineTarget,
    val title: String,
    val features: Set<EngineFeatureId> = emptySet(),
    val contextLimitTokens: Long? = null,
)

/** Fixed credential/workspace route. The model actually used is recorded separately for each turn. */
@Serializable
public data class ExecutionRoute(
    val engine: EngineId,
    val binding: EngineBindingId,
    val authSource: AuthSourceId,
    val revision: AuthRevision,
    val workspace: WorkspaceRef? = null,
)

/** A context-specific check, kept separate from the persistent binding definition. */
@Serializable
public data class BindingCheck(val binding: EngineBindingId, val auth: AuthCheck)

/** AI-engine integration; the application bundle includes the default desktop runtime. */
public val AiEngines: FeatureToggle.Flag = FeatureToggle.Flag(
    "ai.engines",
    "Каталог и сессии ИИ-движков",
    default = true,
)
