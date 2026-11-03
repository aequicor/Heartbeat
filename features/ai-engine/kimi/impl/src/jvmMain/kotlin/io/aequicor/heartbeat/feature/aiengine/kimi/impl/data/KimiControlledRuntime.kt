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

/**
 * Independently pinned executable digests from the public runtime-kimi-2.4.0-hb7 release. This preflight rejects
 * locally self-consistent replacements, but does not bind the subsequently executed process to these bytes and
 * therefore is not runtime admission or a behavioral baseline.
 */
internal fun isKnownKimiRelease(runtime: KimiControlledRuntime): Boolean =
    KIMI_RUNTIME_VERSION == "2.4.0-hb7" && KIMI_SOURCE_COMMIT == "f26e4d91ef8d5d233f6c20b42a150f96924d4f5e" &&
        KIMI_PERMISSION_GATE == 1 &&
        KimiReleaseExecutables[runtime.target] == runtime.fingerprint

private val KimiReleaseExecutables = mapOf(
    "darwin-arm64" to "56da8b554a4de311e80d8642d1c85bc6ec2fe2b73d65e1e2e6a0603959b24e00",
    "darwin-x64" to "7ccf0717d224e496bfbead9962ceb7dac362d33430a3af45c67ce324ed529a79",
    "linux-x64" to "ecdedc6830b94cdab8d71954221d38754cae191871326663e74d77ab021b14e4",
    "win32-arm64" to "1c5c9e156f35f7337061c8c454daad594535c90b6d471a0a5d0a01a4d0c1ef3f",
    "win32-x64" to "9d826c6cd991ab0b87d58efcfd24862e57cf27a40fa077ddd40abc7a7f39d620",
)

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
