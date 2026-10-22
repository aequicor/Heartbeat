package io.aequicor.heartbeat.feature.aiengine.claude.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager

/** Qualified manager contract, like [ClaudeBackend], so it never collides with other engines' managers. */
public interface ClaudeEngineManager : EngineManager

/**
 * What engine management may change for Claude Code: Heartbeat installs its own copy of the official native build,
 * the CLI signs in to a claude.ai account in the browser, and launch settings may name the executable, the runtime
 * (Heartbeat's copy, the Claude desktop app's runtime, optionally a pinned version, or a system installation), the
 * config directory (`CLAUDE_CONFIG_DIR`, which selects the account and the native history) and extra environment.
 * Variables Heartbeat sets itself are reserved. The Claude desktop app is the engine's host application.
 */
internal val ClaudeManagementSpec = ManagementSpec(
    install = InstallSupport.Managed,
    login = LoginSupport.Cli,
    launch = LaunchSpec(
        options = setOf(
            LaunchOption.Executable,
            LaunchOption.Runtime,
            LaunchOption.HomeDirectory,
            LaunchOption.Environment,
        ),
        homeVariable = "CLAUDE_CONFIG_DIR",
        reservedEnvironment = setOf("CLAUDE_CONFIG_DIR", "DISABLE_AUTOUPDATER", "DISABLE_UPDATES"),
        runtimes = setOf(RuntimePreference.Managed, RuntimePreference.HostApp, RuntimePreference.System),
    ),
    hostApp = HostAppSpec("Claude"),
)
