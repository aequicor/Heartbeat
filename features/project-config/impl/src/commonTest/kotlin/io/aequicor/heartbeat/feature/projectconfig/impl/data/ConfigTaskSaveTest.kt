package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEffect
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigTaskSaveTest {
    @Test
    fun `failed file writes leave global and project lessons in their original scope`() = runTest {
        for (project in listOf(null, WorkspaceRef("original"))) {
            val section = FakeSection(ConfigSectionId.Learning, isProjectScoped = true)
            val lesson = FakeEntity("lesson", "style", "tabs", project)
            section.state.value = listOf(lesson)
            val fixture = EffectsFixture(section)
            fixture.folders.writeFailure = ConfigWriteFailure.Io

            val result = assertIs<Internal.TaskDone>(fixture.results(saveLesson()).single())

            assertEquals(ConfigTaskResult.Failed(ConfigTaskFailure.Empty), result.result)
            assertEquals(listOf(lesson), section.state.value)
            assertTrue(section.applied.isEmpty())
            assertTrue(result.bindings.isEmpty())
            assertTrue(fixture.files.isEmpty())
        }
    }

    @Test
    fun `a rejected scope move leaves files unbound for explicit trust`() = runTest {
        val section = FakeSection(ConfigSectionId.Learning, isProjectScoped = true)
        val lesson = FakeEntity("lesson", "style", "tabs")
        section.state.value = listOf(lesson)
        section.rejection = ConfigRejection.Unavailable
        val fixture = EffectsFixture(section)

        val result = assertIs<Internal.TaskDone>(fixture.results(saveLesson()).single())

        assertEquals(ConfigTaskResult.Failed(ConfigTaskFailure.Empty), result.result)
        assertEquals(listOf(lesson), section.state.value)
        assertTrue(result.bindings.isEmpty())
        assertEquals("tabs\n", fixture.files["learning/style.md"])
    }

    @Test
    fun `editing a lesson during the file write does not replace it with the saved copy`() = runTest {
        val section = FakeSection(ConfigSectionId.Learning, isProjectScoped = true)
        val original = FakeEntity("lesson", "style", "tabs")
        val edited = original.copy(text = "spaces", revision = 1)
        section.state.value = listOf(original)
        val fixture = EffectsFixture(section)
        fixture.folders.afterWrite = { section.state.value = listOf(edited) }

        val result = assertIs<Internal.TaskDone>(fixture.results(saveLesson()).single())

        assertEquals(ConfigTaskResult.Failed(ConfigTaskFailure.Empty), result.result)
        assertEquals(listOf(edited), section.state.value)
        assertTrue(section.applied.isEmpty())
        assertTrue(result.bindings.isEmpty())
        assertEquals("tabs\n", fixture.files["learning/style.md"])
    }

    private fun saveLesson() = ProjectConfigEffect.SaveToProject(
        "save",
        PROJECT,
        listOf(ConfigEntityRef(ConfigSectionId.Learning, ConfigEntityKey("lesson"))),
    )
}
