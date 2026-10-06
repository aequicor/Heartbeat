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
)

/** Profile multibinding; resolve lazily because a provider may itself use the engine facade. */
public fun interface AgentToolPolicyProvider {
    /** Null means this provider contributes no policy in this scope. Cancellation must propagate. */
    public suspend fun policy(scope: ToolPolicyScope): ToolPolicy?
}

/**
 * Effective catalog names. Off wins over On; nativeOn includes unchanged adapter defaults.
 * Generation is stable for equal effective policies in a scope, and changes after every effective change,
 * including a return to an earlier policy. It is profile-local and is not a persistent revision.
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
