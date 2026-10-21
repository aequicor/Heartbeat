package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellDialect
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellExecution
import io.aequicor.heartbeat.feature.aiengine.facade.api.POWER_SHELL_UTF8_INITIALIZATION
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8
import java.nio.file.Path
import java.util.Locale

/** Environment for a controlled shell. Startup scripts, exported functions and injected loaders are not inherited. */
internal fun controlledCodingEnvironment(isWindows: Boolean): Map<String, String> =
    codingCommandEnvironment().filterKeys { it.uppercase(Locale.ROOT) in CONTROLLED_ENVIRONMENT }.toMutableMap().apply {
        if (isWindows) {
            keys.filter { it.equals("PSModulePath", ignoreCase = true) }.forEach(::remove)
            put("PSModulePath", windowsPowerShell().parent.resolve("Modules").toString())
            // Command discovery must not consult or mutate the shared per-user module analysis cache.
            put("PSModuleAnalysisCachePath", "NUL")
        }
    }

/** Exact executable, prefix and environment revision shared by evidence and the real process runner. */
internal fun controlledCodingShell(
    isWindows: Boolean,
    environment: Map<String, String> = controlledCodingEnvironment(isWindows),
): NativeShellExecution = NativeShellExecution(
    if (isWindows) NativeShellDialect.PowerShell else NativeShellDialect.Posix,
    if (isWindows) windowsPowerShell().toString() else "/bin/sh",
    if (isWindows) listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command") else listOf("-c"),
    ("coding-shell-v1:" + JsonObject(environment.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        .encodeUtf8().sha256().hex(),
    initialization = if (isWindows) POWER_SHELL_UTF8_INITIALIZATION else "",
)

private fun windowsPowerShell(): Path = Path.of(
    checkNotNull(System.getenv("SystemRoot")) { "Windows system directory unavailable" },
    "System32",
    "WindowsPowerShell",
    "v1.0",
    "powershell.exe",
).toRealPath()

private val CONTROLLED_ENVIRONMENT = setOf(
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "SYSTEMDRIVE",
    "WINDIR",
    "COMSPEC",
    "OS",
    "PROCESSOR_ARCHITECTURE",
    "TEMP",
    "TMP",
    "TMPDIR",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TERM",
    "HOME",
    "USERPROFILE",
    "HOMEDRIVE",
    "HOMEPATH",
    "USER",
    "LOGNAME",
    "USERNAME",
    "APPDATA",
    "LOCALAPPDATA",
    "PROGRAMDATA",
    "PROGRAMFILES",
    "PROGRAMFILES(X86)",
    "JAVA_HOME",
    "ANDROID_HOME",
    "ANDROID_SDK_ROOT",
    "GRADLE_USER_HOME",
)
