package io.aequicor.heartbeat.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteTrainSeedTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val manifest = renderBundleManifest(
        1,
        listOf(
            BundleManifestEntry("foundation", "1", "bundle-foundation-1.jar", "aa", emptyList(), BundleArtifactKind.Base),
            BundleManifestEntry("web", "1", "bundle-web-1.jar", "bb", listOf("foundation")),
        ),
    )

    private fun task(): WriteTrainSeed {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        val task = project.tasks.create("seed", WriteTrainSeed::class.java)
        task.manifest.set(temporaryFolder.newFile("manifest-in.json").apply { writeText(manifest) })
        task.trainId.set("seed-1.0.0")
        task.seedDirectory.set(temporaryFolder.root.resolve("seed"))
        return task
    }

    @Test
    fun `the seed has the listed jars and the manifest with the train id`() {
        val jars = temporaryFolder.newFolder("jars")
        val foundation = jars.resolve("bundle-foundation-1.jar").apply { writeText("f") }
        val web = jars.resolve("bundle-web-1.jar").apply { writeText("w") }
        val stale = jars.resolve("bundle-old-0.jar").apply { writeText("o") }
        val task = task()
        task.jars.from(foundation, web, stale)

        task.write()

        val seed = temporaryFolder.root.resolve("seed")
        assertEquals(listOf("bundle-foundation-1.jar.seed", "bundle-web-1.jar.seed", "manifest.json"), seed.list()!!.sorted())
        val text = seed.resolve("manifest.json").readText()
        assertTrue(text.startsWith("{\n  \"train\": \"seed-1.0.0\","), text)
        assertTrue("\"platformApi\": 1" in text)
        assertFalse(seed.resolve("bundle-old-0.jar.seed").exists())
    }

    @Test
    fun `a listed jar that is missing fails the task`() {
        val task = task()
        task.jars.from(temporaryFolder.newFolder("jars2").resolve("bundle-web-1.jar").apply { writeText("w") })
        assertFailsWith<GradleException> { task.write() }
    }

    @Test
    fun `a train id that is not a plain name fails the task`() {
        val task = task()
        task.trainId.set("../x")
        assertFailsWith<GradleException> { task.write() }
    }
}
