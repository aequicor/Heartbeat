package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.isNewerVersion

/** Frozen host opt-in and native policy must both permit connections before Pi starts any MCP server. */
internal fun piMcpAllowed(startup: PiStartup, plan: PiLaunchPlan): Boolean =
    startup.isMcpEnabled && "mcp" in plan.policy.nativeOn && !plan.policy.isToolFree && !plan.isActionAnalysisRequired

/**
 * Pi added built-in MCP in 0.99.0 and an authoritative `--no-mcp` in 1.0.4. Global extension exclusions alone
 * are insufficient: trusted project settings can enable a built-in again. Unknown versions and the intervening
 * releases cannot enforce this contract. Older releases can run only with MCP off.
 */
internal fun piMcpArguments(version: String?, isAllowed: Boolean): List<String> {
    val stable = version?.takeIf { it.matches(Regex("""\d{1,6}\.\d{1,6}\.\d{1,6}""")) }
    return when {
        stable != null && !isNewerVersion("1.0.4", stable) -> if (isAllowed) emptyList() else listOf("--no-mcp")

        stable != null && isNewerVersion("0.99.0", stable) && !isAllowed -> emptyList()

        else -> {
            log.w { "Selected Pi cannot enforce the MCP launch contract; version 1.0.4 or newer is required" }
            piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        }
    }
}

private val log = Log.tag("PiMcpLaunch")
