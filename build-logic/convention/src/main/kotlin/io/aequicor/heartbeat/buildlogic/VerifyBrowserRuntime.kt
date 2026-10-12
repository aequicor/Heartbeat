package io.aequicor.heartbeat.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest

/**
 * Rejects incomplete platform manifests and mismatched Java/native inputs before tests or packaging.
 * Registered by [JcefRuntimeExtension.registerRuntimeVerification]; [marker] keeps the task up to date until
 * the lock, the patch or the resolved jars change.
 */
@DisableCachingByDefault(because = "Hashing the local jars is cheaper than storing the marker in the build cache")
abstract class VerifyBrowserRuntime : DefaultTask() {
    @get:Input abstract val version: Property<String>

    @get:Input abstract val platform: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patch: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection

    /** Records the verified jars; written only after every check passes. */
    @get:OutputFile abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val lock = JsonSlurper().parse(manifest.get().asFile) as Map<*, *>
        check(lock["artifactVersion"] == version.get()) { "JCEF catalog and source lock versions differ" }
        check(lock["patchSha256"] == patch.get().asFile.sha256()) { "JCEF source patch checksum differs" }
        val natives = lock["nativeArtifacts"] as? Map<*, *> ?: error("Missing JCEF native manifest")
        val required = setOf("windows-amd64", "windows-arm64", "macosx-amd64", "macosx-arm64")
        check(natives.keys == required) { "JCEF release requires all four native targets" }
        val api = lock["apiArtifact"] as? Map<*, *> ?: error("Missing patched JCEF API")
        val entries = listOf(api) + natives.values.map { it as Map<*, *> }
        entries.forEach { entry ->
            check((entry["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true) {
                "JCEF artifact is missing a SHA-256 digest"
            }
        }
        val expected = listOf(api, natives[platform.get()] as Map<*, *>)
        check(artifacts.files.map { it.name }.toSet() == expected.map { it["file"] }.toSet()) {
            "JCEF runtime must contain the exact locked API and host native pair"
        }
        val verified = expected.map { entry ->
            val artifact = artifacts.files.single { it.name == entry["file"] }
            check(artifact.sha256() == entry["sha256"]) { "JCEF artifact checksum mismatch: ${artifact.name}" }
            "${entry["sha256"]}  ${artifact.name}"
        }
        marker.get().asFile.writeText(verified.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
