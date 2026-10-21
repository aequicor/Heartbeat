package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellDialect
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellExecution
import io.aequicor.heartbeat.feature.aiengine.facade.api.POWER_SHELL_UTF8_INITIALIZATION
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Private host configuration, delivered before any model request and removed from Pi's child environment. */
internal data class PiControlledPowerShell(val shell: NativeShellExecution, val manifest: String) {
    override fun toString(): String = "PiControlledPowerShell"
}

internal const val PI_POWERSHELL_MANIFEST = "HEARTBEAT_CONTROLLED_POWERSHELL"
internal const val PI_POWERSHELL_RESOURCE = "heartbeat-powershell.ts"
private const val POWERSHELL_REVISION = "heartbeat-powershell-v1"
private const val VERIFIED_PI_VERSION = "0.87.1"
private val PowerShellArguments = listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command")

/** Trusted launch inputs, never reconstructed from a tool's JSON payload. */
internal data class PiPowerShellHost(
    val verifiedBundled: Path?,
    val bundledVersion: String?,
    val directory: Path,
    val environment: Map<String, String>,
    val systemRoot: Path?,
    val isWindows: Boolean,
) {
    override fun toString(): String = "PiPowerShellHost"
}

/**
 * Only the inspected bundled runtime with our extensions can carry executor evidence. Configured runtimes,
 * discovered code, hosted name collisions and custom environments keep the unknown native executor contract.
 * The supplied system root is read from [host], never from launch settings or tool arguments.
 */
internal fun piControlledPowerShell(
    startup: PiStartup,
    flags: PiFlags,
    plan: PiLaunchPlan,
    host: PiPowerShellHost,
): PiControlledPowerShell? {
    if (!plan.isActionAnalysisRequired || !host.isWindows) return null
    val systemRoot = host.systemRoot?.takeIf(Path::isAbsolute) ?: return null
    if (!hasVerifiedRuntime(startup, host)) return null
    if (flags.isDiscovering || flags.builtins.isNotEmpty()) return null
    if ("powershell" !in plan.policy.nativeOn || "powershell" in plan.policy.nativeOff ||
        plan.hosted?.specifications.orEmpty().any { it.name.equals("powershell", ignoreCase = true) }
    ) {
        return null
    }
    val executable = systemRoot.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").normalize()
    val taskkill = systemRoot.resolve("System32/taskkill.exe")
    if (!Files.isRegularFile(executable) || !Files.isRegularFile(taskkill)) return null
    val fixedEnvironment = host.environment.toMutableMap().also(::retainPiEnvironment).toSortedMap()
    // Prevent implicit module discovery in the user's module directories. Profiles never run.
    fixedEnvironment["PSModulePath"] = executable.parent.resolve("Modules").toString()
    // Keep command discovery independent of the shared per-user module analysis cache.
    fixedEnvironment["PSModuleAnalysisCachePath"] = "NUL"
    val configuration = buildJsonObject {
        put("revision", POWERSHELL_REVISION)
        put("executable", executable.toString())
        put("argumentPrefix", JsonArray(PowerShellArguments.map(::JsonPrimitive)))
        put("initialization", POWER_SHELL_UTF8_INITIALIZATION)
        put("directory", host.directory.toAbsolutePath().normalize().toString())
        put("environment", JsonObject(fixedEnvironment.mapValues { JsonPrimitive(it.value) }))
        put("taskkill", taskkill.toString())
    }.toString()
    val binding = fingerprint(configuration)
    val manifest = buildJsonObject {
        put("configuration", configuration)
        put("binding", binding)
    }.toString()
    return PiControlledPowerShell(
        NativeShellExecution(
            NativeShellDialect.PowerShell,
            executable.toString(),
            PowerShellArguments,
            binding,
            initialization = POWER_SHELL_UTF8_INITIALIZATION,
        ),
        manifest,
    )
}

private fun hasVerifiedRuntime(startup: PiStartup, host: PiPowerShellHost): Boolean {
    if (startup.source != InstallSource.Bundled || startup.executable != host.verifiedBundled ||
        host.verifiedBundled == null
    ) {
        return false
    }
    return host.bundledVersion?.removePrefix("v") == VERIFIED_PI_VERSION &&
        startup.environment.isEmpty() && startup.config.isEmpty
}

/** Model arguments cannot manufacture a connection's launch evidence or transfer it to another tool. */
internal fun PiConnection.controlledShell(call: PiApprovalCall): NativeShellExecution? = controlledPowerShell?.takeIf {
    call.tool == "powershell" && call.executorBinding == it.environmentBinding &&
        call.arguments.string("command") == call.target && call.toolCallId != null
}
