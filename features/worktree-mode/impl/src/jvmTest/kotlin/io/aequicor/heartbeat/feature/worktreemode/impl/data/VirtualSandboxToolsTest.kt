package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildOperation
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildPlan
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeIntent
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxEnabled
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeSandboxRemoteEnabled
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Agent-facing refusals and tool schemas of the virtual sandbox tools. */
class VirtualSandboxToolsTest {
    @Test
    fun `coded store errors become refusals with their hint and other errors stay opaque`() {
        val diverged = assertNotNull(sandboxRefusal(IllegalStateException("SandboxBranchDiverged"), isRemote = true))
        assertTrue(diverged.isError)
        assertTrue(diverged.text.startsWith("Refused: SandboxBranchDiverged. The local branch moved independently"))
        assertEquals(
            "Refused: InvalidBranchName.",
            assertNotNull(sandboxRefusal(IllegalArgumentException("InvalidBranchName"), isRemote = true)).text,
        )
        assertTrue(
            assertNotNull(sandboxRefusal(IllegalStateException("SandboxConflicted"), isRemote = true)).text.length > 30,
        )
        assertNull(sandboxRefusal(IllegalStateException("fatal: unable to access the remote"), isRemote = true))
        assertNull(sandboxRefusal(IllegalStateException(), isRemote = true))
    }

    @Test
    fun `without the remote toggle refusal hints never suggest pushing or fetching`() {
        val codes = listOf(
            "SandboxBranchCheckedOut",
            "SandboxBranchUnknown",
            "SandboxGitFailed",
            "SandboxCommitUnknown",
            "SandboxBranchMoved",
            "SandboxBranchDiverged",
        )
        codes.forEach { code ->
            val hint = assertNotNull(sandboxRefusal(IllegalStateException(code), isRemote = false)).text
            assertFalse("push=true" in hint || "fetch=true" in hint, hint)
            assertTrue(hint.length > "Refused: $code.".length, hint)
        }
        assertTrue(
            "push=true" in assertNotNull(sandboxRefusal(IllegalStateException("SandboxBranchCheckedOut"), true)).text,
        )
    }

    @Test
    fun `without the remote toggle the schemas offer neither push nor fetch`() {
        fun properties(isRemote: Boolean, name: String) =
            sandboxSpecs(isRemote).single { it.name == name }.inputSchema["properties"]!!.jsonObject.keys
        assertFalse("push" in properties(false, "sandbox_commit"))
        assertTrue("force" in properties(false, "sandbox_commit"))
        assertFalse("fetch" in properties(false, "sandbox_checkout"))
        assertFalse("fetch" in properties(false, "sandbox_merge"))
        assertTrue("push" in properties(true, "sandbox_commit"))
        assertTrue("fetch" in properties(true, "sandbox_checkout"))
        assertTrue("fetch" in properties(true, "sandbox_merge"))
        assertEquals(sandboxSpecs(true).map { it.name }, sandboxSpecs(false).map { it.name })
        assertFalse(sandboxSpecs(false).any { "push" in it.description || "fetch" in it.description })
    }

    @Test
    fun `a push or fetch call is refused while the remote toggle is off`() = runTest {
        val sandbox = VirtualFixture()
        try {
            val journal = WorktreeJournal(SettingsStores(), ToolGit(), ToolBuilds(), ToolWorkspaces(), ToolToggles())
            journal.load()
            listOf(
                WorktreeIntent.Public.Prepare(CHAT, WorkspaceRef("project")),
                WorktreeIntent.Public.RunStarted(CHAT, REQUEST),
                WorktreeIntent.Public.RunAccepted(CHAT, REQUEST, SESSION, TURN),
            ).forEach { journal.apply(it) {} }
            val tools = VirtualSandboxTools(sandbox.backend, journal, ToolToggles())
            val context = AgentToolContext(SESSION, WorkspaceRef("checkout"), TURN, REQUEST)
            val push = JsonObject(
                mapOf("branch" to JsonPrimitive("b"), "message" to JsonPrimitive("m"), "push" to JsonPrimitive(true)),
            )
            val refused = tools.execute(context, "sandbox_commit", push)
            assertTrue(refused.isError)
            assertTrue(refused.text.startsWith("Refused: SandboxRemoteDisabled."), refused.text)
            val fetch = JsonObject(mapOf("branch" to JsonPrimitive("b"), "fetch" to JsonPrimitive(true)))
            assertTrue(tools.execute(context, "sandbox_merge", fetch).text.startsWith("Refused: SandboxRemoteDisabled"))
        } finally {
            sandbox.close()
        }
    }

    private companion object {
        const val CHAT = "chat"
        val REQUEST = RequestId("request")
        val TURN = TurnId("turn")
        val SESSION = SessionRef(EngineId("test"), SessionSourceId("test"), "native")
    }
}

/** Worktrees on, sandbox and its remote operations off. */
private class ToolToggles : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(value(toggle))

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    // Flags are Boolean; choices keep their own declared default type.
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(toggle: FeatureToggle<T>): T = when (toggle) {
        is FeatureToggle.Flag -> (toggle != WorktreeSandboxEnabled && toggle != WorktreeSandboxRemoteEnabled) as T
        is FeatureToggle.Choice -> toggle.default
    }
}

private class ToolWorkspaces : LocalWorkspaces {
    override val isAvailable = true
    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
    override suspend fun register(directory: String): LocalWorkspace = error("Unused")
    override suspend fun registerManaged(directory: String) = LocalWorkspace(WorkspaceRef("checkout"), "Checkout")
    override suspend fun resolve(ref: WorkspaceRef) = "/source"
}

private class ToolGit : WorktreeGit {
    override val isAvailable = true
    override suspend fun plan(source: String, identity: String) =
        WorktreeProvision("/source", "/checkout", "/common", "master", "heartbeat/test", "sha")
    override suspend fun trackMain(source: String) = plan(source, "main")
    override suspend fun materialize(record: WorktreeRecord) = Unit
    override suspend fun exists(record: WorktreeRecord) = true
    override suspend fun validatePlan(record: WorktreeRecord, plan: WorktreeBuildPlan) = Unit
    override suspend fun build(record: WorktreeRecord, id: String, command: String): BuildExecution = error("Unused")
    override suspend fun actionPrompt(record: WorktreeRecord, merge: Boolean): String = error("Unused")
    override suspend fun mergeLease(record: WorktreeRecord, id: String): BuildExecution = error("Unused")
    override suspend fun mergePreflight(record: WorktreeRecord): String = error("Unused")
    override suspend fun verifyAction(record: WorktreeRecord, merge: Boolean, pullRequestUrl: String?) = false
}

private class ToolBuilds : WorktreeBuildCoordinator {
    override suspend fun execute(
        execution: BuildExecution,
        isReattachment: Boolean,
        update: suspend (WorktreeBuildOperation) -> Unit,
    ): WorktreeBuildOperation = error("Unused")
    override suspend fun cancel(id: String) = false
    override suspend fun recover(execution: BuildExecution): WorktreeBuildOperation = error("Unused")
    override suspend fun acquireLease(execution: BuildExecution) = Unit
    override suspend fun releaseLease(execution: BuildExecution) = true
}
