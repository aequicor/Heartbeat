package io.aequicor.heartbeat.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BundleManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `manifest lists sorted bundles and escapes strings`() {
        val json = renderBundleManifest(
            platformApi = 3,
            entries = listOf(
                BundleManifestEntry("web", "1.0\"x", "bundle-web.jar", "bb", listOf("foundation", "contracts")),
                BundleManifestEntry("engines", "2", "bundle-engines.jar", "aa", emptyList()),
            ),
        )
        val expected = """
            {
              "platformApi": 3,
              "bundles": [
                {
                  "id": "engines",
                  "version": "2",
                  "jar": "bundle-engines.jar",
                  "sha256": "aa",
                  "dependencies": []
                },
                {
                  "id": "web",
                  "version": "1.0\"x",
                  "jar": "bundle-web.jar",
                  "sha256": "bb",
                  "dependencies": ["contracts", "foundation"]
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, json)
    }

    @Test
    fun `sha256 of file content`() {
        val file = temporaryFolder.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(file))
    }

    @Test
    fun `task writes manifest with jar hashes and base dependencies`() {
        val task = fixture()
        val jars = temporaryFolder.newFolder("jars")
        val jar = jars.resolve(bundleJarName("web", "1.0")).apply { writeText("abc") }
        task.versions.put("web", "1.0")
        task.jars.from(jar)

        task.write()

        val text = task.manifestFile.get().asFile.readText()
        assertEquals(
            renderBundleManifest(
                7,
                listOf(
                    BundleManifestEntry(
                        "web", "1.0", "bundle-web-1.0.jar", sha256Hex(jar), BUNDLE_BASE_DEPENDENCIES,
                    ),
                ),
            ),
            text,
        )
    }

    @Test
    fun `base artifacts are listed with their kind and dependencies`() {
        val task = fixture()
        val jars = temporaryFolder.newFolder("jars")
        val foundation = jars.resolve(bundleJarName("foundation", "1.0")).apply { writeText("f") }
        val contracts = jars.resolve(bundleJarName("contracts", "1.0")).apply { writeText("c") }
        val web = jars.resolve(bundleJarName("web", "1.0")).apply { writeText("w") }
        task.baseArtifacts.put("foundation", "1.0")
        task.baseArtifacts.put("contracts", "1.0")
        task.versions.put("web", "1.0")
        task.jars.from(foundation, contracts, web)

        task.write()

        val text = task.manifestFile.get().asFile.readText()
        val base = BundleArtifactKind.Base
        assertEquals(
            renderBundleManifest(
                7,
                listOf(
                    BundleManifestEntry(
                        "foundation", "1.0", "bundle-foundation-1.0.jar", sha256Hex(foundation), emptyList(), base,
                    ),
                    BundleManifestEntry(
                        "contracts", "1.0", "bundle-contracts-1.0.jar", sha256Hex(contracts), listOf("foundation"), base,
                    ),
                    BundleManifestEntry(
                        "web", "1.0", "bundle-web-1.0.jar", sha256Hex(web), BUNDLE_BASE_DEPENDENCIES,
                    ),
                ),
            ),
            text,
        )
        assertEquals(true, "\"kind\": \"base\"" in text)
        assertEquals(2, Regex("\"kind\"").findAll(text).count())
    }

    @Test
    fun `plain bundles keep the entry format without kind`() {
        val json = renderBundleManifest(1, listOf(BundleManifestEntry("web", "1", "j", "aa", emptyList())))
        assertEquals(false, "kind" in json)
    }

    @Test
    fun `unknown base artifact and missing base jar fail the task`() {
        val task = fixture()
        task.baseArtifacts.put("shell", "1.0")
        assertFailsWith<GradleException> { task.write() }
        val missing = fixture()
        missing.baseArtifacts.put("foundation", "1.0")
        assertFailsWith<GradleException> { missing.write() }
    }

    @Test
    fun `missing jar fails the task`() {
        val task = fixture()
        task.versions.put("web", "1.0")
        assertFailsWith<GradleException> { task.write() }
    }

    private fun fixture(): BundleManifest {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        return project.tasks.create("bundleManifest", BundleManifest::class.java).apply {
            platformApi.set(7)
            manifestFile.set(project.layout.buildDirectory.file("bundles/manifest.json"))
        }
    }
}
