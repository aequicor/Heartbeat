package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Native catalog entry; only gated tools can be enabled beyond the adapter defaults. */
public data class NativeToolSpec(
    val name: String,
    val action: AgentToolAction,
    val isEnabledByDefault: Boolean,
    val isGated: Boolean,
)

/** Persisted policy choice. Absence means the adapter default. */
@Serializable
public enum class ToolSwitch {
    @SerialName("on")
    On,

    @SerialName("off")
    Off,
}

/** One contribution to a session policy. Hosted tools can only be disabled. */
public data class ToolPolicy(val hostedOff: Set<String> = emptySet(), val native: Map<String, ToolSwitch> = emptyMap())

/** Trusted host identity for policy lookup; never supplied through tool arguments. */
public data class ToolPolicyScope(
    val engine: EngineId? = null,
    val workspace: WorkspaceRef? = null,
    val session: SessionRef? = null,
    val target: EngineTarget? = null,
    /** [CreateSessionRequest.preselected] of a session being opened; meaningful only while [session] is null. */
    val preselected: Map<String, Set<String>> = emptyMap(),
)

/** Profile multibinding; resolve lazily because a provider may itself use the engine facade. */
public fun interface AgentToolPolicyProvider {
    /**
     * Null means this provider contributes no policy in this scope. Cancellation must propagate. A provider whose
     * policy source is unavailable throws; [ToolPolicyUnavailable] marks an unavailability it already reported.
     */
    public suspend fun policy(scope: ToolPolicyScope): ToolPolicy?
}

/**
 * Policy source unavailability its provider has already logged. The resolver still fails closed (execution is
 * refused, declarations fall back to defaults) but does not report it again on every lookup.
 */
public open class ToolPolicyUnavailable(message: String = "Tool policy is unavailable") : IllegalStateException(message)

/**
 * Effective catalog names. Off wins over On; nativeOn includes unchanged adapter defaults.
 * Generation changes after every effective change in a scope, including a return to an earlier policy, and is
 * stable for equal effective policies while the profile still remembers the scope. The memory is bounded: a scope
 * not resolved for long may get a new generation for an equal policy, so a changed generation means only a possible
 * change and the consumer's reload may be redundant. It is profile-local and is not a persistent revision.
 */
public data class ResolvedToolPolicy(
    val nativeOn: Set<String> = emptySet(),
    val nativeOff: Set<String> = emptySet(),
    val hostedDenied: Set<String> = emptySet(),
    val generation: Long = 0,
)

/** Tool identity and trust category for settings, including currently unavailable tools. */
public data class ToolCatalogEntry(val name: String, val action: AgentToolAction)

/** Stable group identity and user-facing title; entries contain no workspace or execution capability. */
public data class ToolGroup(val id: String, val title: String, val tools: List<ToolCatalogEntry>)

/** Builds static catalog metadata without retaining a schema or session context. */
public fun List<AgentToolSpec>.toolCatalog(): List<ToolCatalogEntry> = map { ToolCatalogEntry(it.name, it.action) }

/** Enables gated native tools beyond adapter defaults; disabling tools does not require this flag. */
public val HarnessNativeTools: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "harness.native_tools",
    description = "Включение нативных инструментов через харнессы",
)
