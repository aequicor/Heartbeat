package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/**
 * Only the packaged, hash-checked Heartbeat runtime may execute agent tools. System CLI settings and
 * login remain shared, but PATH and an installed stock CLI cannot substitute for the mandatory gate.
 * Called on the injected IO dispatcher; no executable is downloaded or repaired during resolution.
 */
internal fun kimiControlledExecutable(
    resourcesDirectory: String? = System.getProperty("compose.application.resources.dir"),
    isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
): String {
    val root = resourcesDirectory?.let { File(it, "kimi") }
    val executable = root?.resolve(if (isWindows) "kimi.exe" else "kimi")
    val manifest = root?.resolve("runtime.properties")
    val properties = Properties()
    if (executable?.isFile == true && executable.canExecute() && manifest?.isFile == true) {
        manifest.inputStream().use(properties::load)
        val digest = MessageDigest.getInstance("SHA-256")
        executable.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(bytes)
            while (count >= 0) {
                digest.update(bytes, 0, count)
                count = input.read(bytes)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        val isPinned = properties.getProperty("version") == "2.4.0-hb6" &&
            properties.getProperty("sourceCommit") == "f26e4d91ef8d5d233f6c20b42a150f96924d4f5e" &&
            properties.getProperty("permissionGate") == "1"
        if (isPinned && properties.getProperty("sha256") == hash) {
            return executable.absolutePath
        }
    }
    throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
}
