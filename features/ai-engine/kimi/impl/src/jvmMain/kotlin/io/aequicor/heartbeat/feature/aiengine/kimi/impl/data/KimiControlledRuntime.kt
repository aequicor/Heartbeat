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
 * Resolves a runtime whose adjacent manifest matches its bytes and the expected pin. This local consistency check
 * does not independently establish provenance if both files changed. PATH, custom launch settings and a stock CLI
 * cannot replace its mandatory gate. Resolves files on the caller's IO dispatcher without downloading.
 * A supplied managed candidate is checked in place; a bad candidate never falls back to the installer seed.
 */
internal fun kimiControlledExecutable(
    resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    managed: ManagedInstall? = null,
): String = kimiControlledRuntime(resourcesDirectory, isWindows, managed).executable

/** Identity checked against the adjacent artifact manifest, not an independent trust anchor; never log a path. */
internal data class KimiControlledRuntime(val executable: String, val fingerprint: String, val target: String) {
    override fun toString(): String = "KimiControlledRuntime"
}

/** [checkCancellation] is called while hashing so a large artifact cannot retain a cancelled panel request. */
internal fun kimiControlledRuntime(
    resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    managed: ManagedInstall? = null,
    checkCancellation: () -> Unit = {},
): KimiControlledRuntime {
    checkCancellation()
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
    val hash = kimiExecutableHash(executable, checkCancellation)
    val isPinned = mapOf(
        "version" to KIMI_RUNTIME_VERSION,
        "sourceCommit" to KIMI_SOURCE_COMMIT,
        "permissionGate" to KIMI_PERMISSION_GATE.toString(),
        "sha256" to hash,
    ).all { (key, value) -> properties.getProperty(key) == value }
    if (!isPinned) missingKimiRuntime()
    val target = kimiRuntimeTarget() ?: missingKimiRuntime()
    if (properties.getProperty("target") != target) missingKimiRuntime()
    checkCancellation()
    return KimiControlledRuntime(executable.absolutePath, hash, target)
}

private fun managedKimiExecutable(managed: ManagedInstall, binary: String): File {
    val executable = File(managed.executable)
    if (managed.version != KIMI_RUNTIME_VERSION || executable.name != binary) missingKimiRuntime()
    return executable
}

private fun kimiExecutableHash(executable: File, checkCancellation: () -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    executable.inputStream().use { input ->
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = input.read(bytes)
        while (count >= 0) {
            checkCancellation()
            digest.update(bytes, 0, count)
            count = input.read(bytes)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
}

private fun missingKimiRuntime(): Nothing =
    throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
