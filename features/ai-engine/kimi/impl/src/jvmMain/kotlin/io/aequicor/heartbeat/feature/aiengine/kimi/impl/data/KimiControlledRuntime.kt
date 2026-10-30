package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/**
 * Only a pinned, hash-checked Heartbeat runtime may execute agent tools. PATH, custom launch settings and a stock
 * CLI cannot replace its mandatory gate. Resolves files on the caller's IO dispatcher without downloading.
 * A supplied managed candidate is checked in place; a bad candidate never falls back to the installer seed.
 */
internal fun kimiControlledExecutable(
    resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    managed: ManagedInstall? = null,
): String {
    val binary = if (isWindows) "kimi.exe" else "kimi"
    val executable = if (managed != null) {
        managedKimiExecutable(managed, binary)
    } else {
        resourcesDirectory?.let { File(it, "kimi/$binary") }
    } ?: missingKimiRuntime()
    val manifest = executable.parentFile.resolve("runtime.properties")
    if (!executable.isFile || !executable.canExecute() || !manifest.isFile) missingKimiRuntime()
    val properties = Properties()
    manifest.inputStream().use(properties::load)
    val isPinned = mapOf(
        "version" to KIMI_RUNTIME_VERSION,
        "sourceCommit" to KIMI_SOURCE_COMMIT,
        "permissionGate" to KIMI_PERMISSION_GATE.toString(),
        "sha256" to kimiExecutableHash(executable),
    ).all { (key, value) -> properties.getProperty(key) == value }
    if (!isPinned) missingKimiRuntime()
    return executable.absolutePath
}

private fun managedKimiExecutable(managed: ManagedInstall, binary: String): File {
    val executable = File(managed.executable)
    if (managed.version != KIMI_RUNTIME_VERSION || executable.name != binary) missingKimiRuntime()
    return executable
}

private fun kimiExecutableHash(executable: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    executable.inputStream().use { input ->
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(bytes)
        while (count >= 0) {
            digest.update(bytes, 0, count)
            count = input.read(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
}

private fun missingKimiRuntime(): Nothing =
    throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
