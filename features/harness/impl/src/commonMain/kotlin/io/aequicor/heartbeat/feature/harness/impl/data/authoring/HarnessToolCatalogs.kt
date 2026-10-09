package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec

/** Static tool metadata for policy authoring; reads never start an engine or a session. */
internal interface HarnessToolCatalogs {
    /** Heartbeat tool groups, including currently unavailable tools. */
    fun hosted(): List<ToolGroup>

    /** Registered, currently enabled engines in catalog order. */
    fun engines(): List<EngineDescriptor>

    /** Effective native policy for a trusted scope. */
    suspend fun effective(scope: ToolPolicyScope): ResolvedToolPolicy

    /** Checks a proposed policy against the catalogs; null means valid. */
    fun problem(spec: ToolPolicySpec): String? = toolPolicyProblem(spec, hosted(), engines())
}

/** Lazy dispatcher access breaks the contribution cycle: the dispatcher is built from this feature's tools too. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class FacadeHarnessToolCatalogs(
    private val tools: Lazy<ProfileAgentTools>,
    private val facade: Lazy<EngineFacade>,
) : HarnessToolCatalogs {
    override fun hosted(): List<ToolGroup> = tools.value.catalog()

    override fun engines(): List<EngineDescriptor> = facade.value.engines.state.value.map { it.descriptor }

    override suspend fun effective(scope: ToolPolicyScope): ResolvedToolPolicy = tools.value.nativeTools(scope)
}

/**
 * Harness tools stay available so that a harness cannot lock the agent out of its own repair. Only gated native
 * tools can be switched on; a native name colliding with a Heartbeat tool must use hosted_off.
 */
internal fun toolPolicyProblem(
    spec: ToolPolicySpec,
    groups: List<ToolGroup>,
    engines: List<EngineDescriptor>,
): String? {
    val hosted = groups.flatMap { it.tools }.map { it.name }.toSet()
    val unknown = spec.hostedOff.filter { it !in hosted }
    val own = spec.hostedOff.filter { it.startsWith("harness_") || it.startsWith(HarnessTools.SCRIPT_PREFIX) }
    val registered = engines.associateBy { it.id.value }
    return when {
        own.isNotEmpty() -> "harness tools cannot be turned off by a harness"

        unknown.isNotEmpty() -> "unknown Heartbeat tools: ${unknown.joinToString()}; see harness_tools_catalog"

        else -> spec.native.entries.firstNotNullOfOrNull { (engine, switches) ->
            val descriptor = registered[engine] ?: return@firstNotNullOfOrNull "unknown or disabled engine $engine"
            val native = descriptor.nativeTools.associateBy { it.name }
            switches.entries.firstNotNullOfOrNull { (tool, switch) ->
                val known = native[tool]
                when {
                    known == null -> "$engine has no native tool $tool"
                    tool in hosted -> "$tool is also a Heartbeat tool; use hosted_off"
                    switch == ToolSwitch.On && !known.isGated -> "$engine tool $tool can only be turned off"
                    else -> null
                }
            }
        }
    }
}
