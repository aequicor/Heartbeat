package io.aequicor.heartbeat.buildlogic

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertTrue

class BaseArtifactJarsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `absent base artifact dir is an empty collection that resolves in the task graph`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        val task = project.tasks.create("bundleManifest", BundleManifest::class.java)
        task.jars.from(project.baseArtifactJars())

        assertTrue(task.taskDependencies.getDependencies(task).isEmpty())
        assertTrue(task.jars.files.isEmpty())
    }
}
