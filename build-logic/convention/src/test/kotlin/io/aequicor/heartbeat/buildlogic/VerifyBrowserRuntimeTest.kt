package io.aequicor.heartbeat.buildlogic

import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VerifyBrowserRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `published four-host release remains valid on its own platforms`() {
        val task = fixture(legacyTargets, "macosx-arm64")
        task.verify()
        assertTrue(task.marker.get().asFile.readText().contains("jcef-natives-macosx-arm64"))
    }

    @Test
    fun `Linux runtime requires a complete five-host release and verifies its native jar`() {
        val task = fixture(legacyTargets + "linux-amd64", "linux-amd64")
        task.verify()
        assertTrue(task.marker.get().asFile.readText().contains("jcef-natives-linux-amd64"))

        task.artifacts.files.single { it.name.contains("natives") }.writeText("corrupt JNI")
        assertFailsWith<IllegalStateException> { task.verify() }
    }

    @Test
    fun `Linux cannot silently reuse a release without its native target`() {
        val task = fixture(legacyTargets, "linux-amd64")
        assertFailsWith<IllegalStateException> { task.verify() }
    }

    @Test
    fun `a declared Linux target cannot be omitted from the published artifact manifest`() {
        val task = fixture(legacyTargets + "linux-amd64", "macosx-arm64", nativeTargets = legacyTargets)
        assertFailsWith<IllegalStateException> { task.verify() }
    }

    private fun fixture(
        targets: Set<String>,
        host: String,
        nativeTargets: Set<String> = targets,
    ): VerifyBrowserRuntime {
        val root = temporaryFolder.newFolder()
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val patch = File(root, "runtime.patch").apply { writeText("patched API contract") }
        val api = File(root, "jcef-api-fixture.jar").apply { writeText("API") }
        val natives = nativeTargets.associateWith { target ->
            File(root, "jcef-natives-$target-fixture.jar").apply { writeText("JNI $target") }
        }
        val lock = File(root, "source-lock.json").apply {
            writeText(
                JsonOutput.toJson(
                    mapOf(
                        "artifactVersion" to "fixture",
                        "patchSha256" to digest(patch),
                        "cefDistributions" to targets.associateWith { emptyMap<String, String>() },
                        "apiArtifact" to entry(api),
                        "nativeArtifacts" to natives.mapValues { entry(it.value) },
                    ),
                ),
            )
        }
        return project.tasks.create("verifyBrowserRuntime", VerifyBrowserRuntime::class.java).apply {
            version.set("fixture")
            platform.set(host)
            manifest.set(lock)
            this.patch.set(patch)
            artifacts.from(api, natives[host])
            marker.set(File(root, "verified.txt"))
        }
    }

    private fun entry(file: File): Map<String, String> = mapOf("file" to file.name, "sha256" to digest(file))

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private companion object {
        val legacyTargets = setOf("macosx-amd64", "macosx-arm64", "windows-amd64", "windows-arm64")
    }
}
