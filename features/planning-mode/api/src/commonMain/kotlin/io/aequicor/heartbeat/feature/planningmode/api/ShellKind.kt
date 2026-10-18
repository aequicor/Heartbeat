package io.aequicor.heartbeat.feature.planningmode.api

/** Shell a command runs in. */
public enum class ShellKind {
    /** `sh`, `bash`, `zsh` and compatible shells. */
    Posix,

    /** Windows PowerShell or PowerShell 7. */
    PowerShell,
}
