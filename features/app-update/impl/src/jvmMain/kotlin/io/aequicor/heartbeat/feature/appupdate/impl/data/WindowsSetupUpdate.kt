package io.aequicor.heartbeat.feature.appupdate.impl.data

import java.nio.file.Path

/**
 * Updates a Windows installation with the release's Inno Setup installer: it installs over the existing installation
 * (same AppId, the user's data stays) without pages, closing what still runs from it. A per-user installation needs
 * no administrator; an all-users one shows the UAC prompt.
 */
internal object WindowsSetupUpdate {
    /** The helper command that runs [installer] after process [pid] quits and starts [launcher] again. */
    fun helperCommand(script: Path, pid: Long, installer: Path, launcher: Path, installLog: Path): List<String> =
        listOf(
            powerShell(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            "$script",
            "-ProcessId",
            "$pid",
            "-Installer",
            "$installer",
            "-App",
            "$launcher",
            "-InstallLog",
            "$installLog",
        )

    /** Windows PowerShell by its system path, so that no `powershell.exe` earlier on PATH runs instead. */
    private fun powerShell(): String = System.getenv("SystemRoot")
        ?.let { Path.of(it, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString() }
        ?: "powershell.exe"
}
