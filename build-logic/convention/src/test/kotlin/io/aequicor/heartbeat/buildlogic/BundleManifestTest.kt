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
    fun `sha256 of bytes matches the one of a file with the same content`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
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
    fun `launcher entry carries its level and signature after the kind`() {
        val json = renderBundleManifest(
            2,
            listOf(
                BundleManifestEntry(
                    "launcher", "t1", "bundle-launcher-t1.jar", "ab", emptyList(), BundleArtifactKind.Launcher, 4, "c2ln",
                ),
            ),
        )
        val expected = """
            {
              "platformApi": 2,
              "bundles": [
                {
                  "id": "launcher",
                  "version": "t1",
                  "jar": "bundle-launcher-t1.jar",
                  "sha256": "ab",
                  "kind": "launcher",
                  "launcherApi": 4,
                  "signature": "c2ln",
                  "dependencies": []
                }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, json)
    }

    @Test
    fun `launcher entry is signed over the record of its jar when a key is given`() {
        val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val task = fixture()
        val jars = temporaryFolder.newFolder("jars")
        val launcher = jars.resolve(bundleJarName("launcher", "t1")).apply { writeText("launcher") }
        task.baseArtifacts.put("launcher", "t1")
        task.jars.from(launcher)
        task.launcherApi.set(5)
        task.launcherSigningKey.set(java.util.Base64.getEncoder().encodeToString(keys.private.encoded))

        task.write()

        val text = task.manifestFile.get().asFile.readText()
        val signature = Regex("\"signature\": \"([^\"]+)\"").find(text)!!.groupValues[1]
        val verifier = java.security.Signature.getInstance("Ed25519")
        verifier.initVerify(keys.public)
        verifier.update(launcherRecordCanonical("launcher", "t1", sha256Hex(launcher), "launcher", 5))
        assertEquals(true, verifier.verify(java.util.Base64.getDecoder().decode(signature)))
        assertEquals(true, "\"kind\": \"launcher\"" in text && "\"launcherApi\": 5" in text)
    }

    @Test
    fun `the key digest ignores whitespace and is absent without a usable key`() {
        val task = fixture()
        assertEquals(null, task.launcherSigningKeyDigest.orNull)
        task.launcherSigningKey.set("  \n ")
        assertEquals(null, task.launcherSigningKeyDigest.orNull)
        task.launcherSigningKey.set("abc")
        val digest = task.launcherSigningKeyDigest.get()
        assertEquals(sha256Hex("abc".toByteArray()), digest)
        task.launcherSigningKey.set(" ab\nc \n")
        assertEquals(digest, task.launcherSigningKeyDigest.get())
        task.launcherSigningKey.set("abd")
        assertEquals(false, digest == task.launcherSigningKeyDigest.get())
    }

    @Test
    fun `launcher entry is signed with a key that openssl wrapped into lines`() {
        val keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val wrapped = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded)
        val task = fixture()
        val jars = temporaryFolder.newFolder("jars")
        task.baseArtifacts.put("launcher", "t1")
        task.jars.from(jars.resolve(bundleJarName("launcher", "t1")).apply { writeText("launcher") })
        task.launcherApi.set(2)
        task.launcherSigningKey.set(wrapped + "\n")

        task.write()

        assertEquals(true, "\"signature\"" in task.manifestFile.get().asFile.readText())
    }

    @Test
    fun `launcher entry without a key is written unsigned and needs a level`() {
        val task = fixture()
        val jars = temporaryFolder.newFolder("jars")
        task.baseArtifacts.put("launcher", "t1")
        task.jars.from(jars.resolve(bundleJarName("launcher", "t1")).apply { writeText("launcher") })
        assertFailsWith<GradleException> { task.write() }
        task.launcherApi.set(2)
        task.write()
        val text = task.manifestFile.get().asFile.readText()
        assertEquals(false, "signature" in text)
        assertEquals(true, "\"launcherApi\": 2" in text)
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
