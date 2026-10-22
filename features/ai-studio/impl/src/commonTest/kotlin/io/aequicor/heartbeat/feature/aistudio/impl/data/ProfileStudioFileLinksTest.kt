package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileReader
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProfileStudioFileLinksTest {
    private val reader = Reader()
    private val access = Records()

    @Test
    fun `worktree chats resolve links against their own checkout`() = runTest {
        access.records["chat"] = chat().copy(executionWorkspace = WorkspaceRef("checkout"))
        assertEquals("/checkout/plan.md", links().resolve("plan.md", "chat", "project"))
        assertEquals("/checkout", reader.bases.single())
    }

    @Test
    fun `project chats and new chats resolve against the project`() = runTest {
        access.records["chat"] = chat()
        assertEquals("/project/a.kt", links().resolve("a.kt", "chat", null))
        assertEquals("/project/b.kt", links().resolve("b.kt", null, "project"))
    }

    @Test
    fun `unknown chats and chats without a project keep only absolute links`() = runTest {
        assertEquals(null, links().resolve("a.kt", "missing", null))
        access.records["chat"] = chat().copy(projectId = null)
        assertEquals(null, links().resolve("a.kt", "chat", null))
        assertEquals("/abs/a.kt", links().resolve("/abs/a.kt", "chat", null))
    }

    @Test
    fun `availability follows the toggle and the platform`() = runTest {
        assertTrue(links(isEnabled = true).availability.first())
        assertFalse(links(isEnabled = false).availability.first())
        val unsupported = ProfileStudioFileLinks(lazyOf(access), Workspaces(), Reader(false), Toggles(true))
        assertFalse(unsupported.availability.first())
    }

    private fun links(isEnabled: Boolean = true) =
        ProfileStudioFileLinks(lazyOf(access), Workspaces(), reader, Toggles(isEnabled))

    private fun chat() = StudioChatRecord("chat", "Chat", Instant.fromEpochSeconds(0), projectId = "project")

    private class Reader(override val isAvailable: Boolean = true) : StudioFileReader {
        val bases = mutableListOf<String?>()

        override suspend fun resolve(path: String, base: String?): String? {
            bases += base
            return if (path.startsWith("/")) path else base?.let { "$it/$path" }
        }

        override suspend fun read(path: String): StudioFileContent = StudioFileContent.Unavailable

        override suspend fun modifiedAt(path: String): Long? = null
    }

    private class Records : StudioConfigurationAccess {
        val records = mutableMapOf<String, StudioChatRecord>()

        override suspend fun configurationRecord(id: String): StudioChatRecord =
            records[id] ?: error("Unknown studio conversation")

        override suspend fun configurationAnchor(id: String): FeedbackAnchor = error("unused")

        override suspend fun configurationSession(id: String, target: EngineTarget): ActiveSession = error("unused")

        override suspend fun saveConfiguration(id: String, settings: StudioSessionSettings) = error("unused")

        override suspend fun validateConfigurationTarget(target: EngineTarget) = error("unused")

        override fun configurationModels(): List<StudioModel> = error("unused")

        override fun configurationState(id: String, state: StudioSessionConfiguration) = error("unused")
    }

    private class Workspaces : LocalWorkspaces {
        override val isAvailable = true

        override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())

        override suspend fun register(directory: String): LocalWorkspace = error("unused")

        override suspend fun resolve(ref: WorkspaceRef): String? = "/${ref.value}"
    }

    private class Toggles(private val isEnabled: Boolean) : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // The file links request only their flag.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
    }
}
