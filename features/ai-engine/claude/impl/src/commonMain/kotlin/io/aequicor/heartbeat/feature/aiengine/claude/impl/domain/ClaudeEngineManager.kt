package io.aequicor.heartbeat.feature.aiengine.claude.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager

/** Qualified manager contract, like [ClaudeBackend], so it never collides with other engines' managers. */
public interface ClaudeEngineManager : EngineManager

/**
 * What engine management may change for Claude Code: Heartbeat installs its own copy of the official native build,
 * the account is still added through the connection wizard, and launch settings may name the executable, the
 * config directory (`CLAUDE_CONFIG_DIR`, which selects the account and the native history) and extra environment.
 * Variables Heartbeat sets itself are reserved.
 */
internal val ClaudeManagementSpec = ManagementSpec(
    install = InstallSupport.Managed,
    login = LoginSupport.Connections,
    launch = LaunchSpec(
        options = setOf(LaunchOption.Executable, LaunchOption.HomeDirectory, LaunchOption.Environment),
        homeVariable = "CLAUDE_CONFIG_DIR",
        reservedEnvironment = setOf("CLAUDE_CONFIG_DIR", "DISABLE_AUTOUPDATER", "DISABLE_UPDATES"),
    ),
)
