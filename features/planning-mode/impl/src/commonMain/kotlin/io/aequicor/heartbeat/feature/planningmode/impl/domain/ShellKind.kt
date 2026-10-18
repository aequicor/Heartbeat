package io.aequicor.heartbeat.feature.planningmode.impl.domain

/** Shell a command runs in. */
internal enum class ShellKind {
    /** `sh`, `bash`, `zsh` and compatible shells. */
    Posix,

    /** Windows PowerShell or PowerShell 7. */
    PowerShell,
}
