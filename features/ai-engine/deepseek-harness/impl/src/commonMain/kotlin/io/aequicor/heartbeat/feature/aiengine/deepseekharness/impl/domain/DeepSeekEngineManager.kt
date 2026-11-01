package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager

/** Qualified manager contract of the DeepSeek Harness CLI, so it never collides with other engines' managers. */
public interface DeepSeekEngineManager : EngineManager

/**
 * What engine management may change for DeepSeek Harness: the `dsh` CLI belongs to the user's own installation
 * (the desktop app or a standalone command), Heartbeat only discovers and runs it, and sign-in is a connection read
 * from the shared DeepSeek Harness login instead of a CLI flow owned by Heartbeat.
 */
internal val DeepSeekManagementSpec = ManagementSpec(
    install = InstallSupport.External,
    login = LoginSupport.Connections,
)
