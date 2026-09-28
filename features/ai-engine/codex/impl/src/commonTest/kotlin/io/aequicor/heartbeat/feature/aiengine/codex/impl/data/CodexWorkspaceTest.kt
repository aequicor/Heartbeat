package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CodexWorkspaceTest {
    @Test
    fun `new and resumed native threads receive the registered project directory`() = runTest {
        val workspace = WorkspaceRef("project")
        listOf(false, true).forEach { resume ->
            val fixture = Fixture(this)
            fixture.workspacePaths[workspace] = "/projects/Heartbeat"
            if (resume) {
                val ref = SessionRef(fixture.target.engine, fixture.environment.config.historySource, "thread")
                fixture.runtime.attach(ref, ResumeSessionRequest(fixture.target, workspace = workspace))
            } else {
                fixture.runtime.create(CreateSessionRequest(fixture.target, workspace = workspace))
            }
            val method = if (resume) "thread/resume" else "thread/start"
            val params = fixture.wire.written.single { it.text("method") == method }.obj("params")
            assertEquals("/projects/Heartbeat", params.text("cwd"))
            fixture.runtime.close()
        }
    }

    @Test
    fun `unknown project fails before starting a thread`() = runTest {
        val fixture = Fixture(this)
        assertFailsWith<EngineException> {
            fixture.runtime.create(CreateSessionRequest(fixture.target, workspace = WorkspaceRef("unknown")))
        }
        assertFalse(fixture.wire.written.any { it.text("method") == "thread/start" })
        fixture.runtime.close()
    }

    @Test
    fun `explicit host workspace configuration stays compatible`() = runTest {
        val workspace = WorkspaceRef("legacy")
        val fixture = Fixture(this, configuration = CodexLocalConfiguration(workspaces = mapOf(workspace to "/legacy")))
        fixture.runtime.create(CreateSessionRequest(fixture.target, workspace = workspace))
        val params = fixture.wire.written.single { it.text("method") == "thread/start" }.obj("params")
        assertEquals("/legacy", params.text("cwd"))
        fixture.runtime.close()
    }
}
