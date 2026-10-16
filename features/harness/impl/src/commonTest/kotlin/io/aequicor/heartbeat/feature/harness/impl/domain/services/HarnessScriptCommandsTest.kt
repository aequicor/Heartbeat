package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommand
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandRefusal
import io.aequicor.heartbeat.feature.harness.api.script.ScriptCommandResult
import io.aequicor.heartbeat.feature.harness.impl.data.services.CommandToggles
import io.aequicor.heartbeat.feature.harness.impl.data.services.RecordingLocalCommands
import io.aequicor.heartbeat.feature.harness.impl.data.services.SavedProjectScopes
import io.aequicor.heartbeat.feature.harness.impl.data.services.commandsTestFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val PROJECT = WorkspaceRef("project")
private val OTHER = WorkspaceRef("other")
private val TEST = ItemName("test")
private val GRADLE = ScriptCommand(
    "./gradlew",
    listOf("test", "--tests", "{filter}"),
    mapOf("filter" to Regex("[A-Za-z.*]+")),
)

class HarnessScriptCommandsTest {
    @Test
    fun `a declared command runs its fixed words with a matching parameter`() = runTest {
        val fixture = CommandsFixture(this)
        fixture.commands.define(TEST, GRADLE)
        fixture.publish()

        val result = fixture.commands.run(TEST, PROJECT, mapOf("filter" to "io.*Test"))

        assertEquals(ScriptCommandResult.Exited(0, "ok"), result)
        assertEquals(
            listOf(LocalCommandRequest(PROJECT, "./gradlew", listOf("test", "--tests", "io.*Test"), 1.minutes)),
            fixture.local.requests,
        )
    }

    @Test
    fun `parameters must match exactly and completely`() = runTest {
        val fixture = CommandsFixture(this)
        fixture.commands.define(TEST, GRADLE)
        fixture.publish()

        val invalid = listOf(
            mapOf("filter" to "a; rm -rf ~"),
            mapOf("filter" to "-x"),
            emptyMap(),
            mapOf("filter" to "a", "extra" to "b"),
            mapOf("filter" to "a".repeat(HarnessLimits.COMMAND_PARAMETER_CHARS + 1)),
        )

        invalid.forEach { parameters ->
            assertEquals(
                ScriptCommandResult.Refused(ScriptCommandRefusal.InvalidParameter),
                fixture.commands.run(TEST, PROJECT, parameters),
            )
        }
        assertTrue(fixture.local.requests.isEmpty())
    }

    @Test
    fun `declarations are checked before they are stored`() = runTest {
        val commands = CommandsFixture(this).commands
        val invalid = listOf(
            ScriptCommand(" "),
            ScriptCommand("{tool}", parameters = mapOf("tool" to Regex(".*"))),
            ScriptCommand("git", listOf("--repo={repo}"), mapOf("repo" to Regex(".*"))),
            ScriptCommand("git", parameters = mapOf("unused" to Regex(".*"))),
            ScriptCommand("git", listOf("{x}"), mapOf("Bad" to Regex(".*"))),
            ScriptCommand("git", timeout = 31.minutes),
            ScriptCommand("git", List(HarnessLimits.COMMAND_ARGUMENTS + 1) { "a" }),
        )

        invalid.forEach { command -> assertFailsWith<IllegalArgumentException> { commands.define(TEST, command) } }
    }

    @Test
    fun `commands are declared only during evaluation`() = runTest {
        val fixture = CommandsFixture(this)
        fixture.commands.define(TEST, ScriptCommand("git"))

        assertFailsWith<IllegalStateException> { fixture.commands.define(TEST, ScriptCommand("git")) }
        fixture.publish()
        assertFailsWith<IllegalStateException> { fixture.commands.define(ItemName("late"), ScriptCommand("git")) }
    }

    @Test
    fun `runs require the published activation`() = runTest {
        val fixture = CommandsFixture(this)
        fixture.commands.define(TEST, ScriptCommand("git"))

        assertFailsWith<IllegalStateException> { fixture.commands.run(TEST, PROJECT) }
        fixture.publish()
        fixture.access.isActive = false
        assertFailsWith<IllegalStateException> { fixture.commands.run(TEST, PROJECT) }
        fixture.access.isActive = true
        fixture.commands.close()
        assertEquals(
            ScriptCommandResult.Refused(ScriptCommandRefusal.NotDefined),
            fixture.commands.run(TEST, PROJECT),
        )
        assertTrue(fixture.local.requests.isEmpty())
    }

    @Test
    fun `hooks cannot run commands`() = runTest {
        val fixture = CommandsFixture(this)
        fixture.commands.define(TEST, ScriptCommand("git"))
        fixture.publish()

        assertFailsWith<IllegalStateException> {
            withContext(HarnessOriginContext(HarnessCallOrigin(isHookRestricted = true))) {
                fixture.commands.run(TEST, PROJECT)
            }
        }
    }

    @Test
    fun `the toggle platform and project gate every run`() = runTest {
        val toggles = CommandToggles(isEnabled = false)
        val fixture = CommandsFixture(this, toggles = toggles)
        fixture.commands.define(TEST, ScriptCommand("git"))
        fixture.publish()

        assertEquals(ScriptCommandResult.Refused(ScriptCommandRefusal.Disabled), fixture.commands.run(TEST, PROJECT))
        toggles.isEnabled = true
        assertEquals(ScriptCommandResult.Refused(ScriptCommandRefusal.NoProject), fixture.commands.run(TEST, null))
        assertEquals(
            ScriptCommandResult.Refused(ScriptCommandRefusal.NoProject),
            fixture.commands.run(TEST, WorkspaceRef("unknown")),
        )
        assertEquals(
            ScriptCommandResult.Refused(ScriptCommandRefusal.NotDefined),
            fixture.commands.run(ItemName("other"), PROJECT),
        )
        assertTrue(fixture.local.requests.isEmpty())

        val mobile = CommandsFixture(this, local = RecordingLocalCommands(isAvailable = false))
        mobile.commands.define(TEST, ScriptCommand("git"))
        mobile.publish()
        assertEquals(ScriptCommandResult.Refused(ScriptCommandRefusal.Unsupported), mobile.commands.run(TEST, PROJECT))
    }

    @Test
    fun `a project scoped harness runs only in its projects`() = runTest {
        val fixture = CommandsFixture(this, scope = HarnessScope.Projects(setOf(PROJECT)))
        fixture.commands.define(TEST, ScriptCommand("git"))
        fixture.publish()

        assertEquals(
            ScriptCommandResult.Refused(ScriptCommandRefusal.OutsideScope),
            fixture.commands.run(TEST, OTHER),
        )
        assertEquals(ScriptCommandResult.Exited(0, "ok"), fixture.commands.run(TEST, PROJECT))
    }

    @Test
    fun `a harness runs a bounded number of commands at once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val local = RecordingLocalCommands().apply { before = { gate.await() } }
        val fixture = CommandsFixture(this, local = local)
        fixture.commands.define(TEST, ScriptCommand("git"))
        fixture.publish()

        val running = List(HarnessLimits.COMMAND_RUNS) { async { fixture.commands.run(TEST, PROJECT) } }
        runCurrent()
        assertEquals(ScriptCommandResult.Refused(ScriptCommandRefusal.Busy), fixture.commands.run(TEST, PROJECT))
        gate.complete(Unit)
        running.forEach { assertEquals(ScriptCommandResult.Exited(0, "ok"), it.await()) }
        assertEquals(ScriptCommandResult.Exited(0, "ok"), fixture.commands.run(TEST, PROJECT))
    }

    @Test
    fun `local outcomes become script results`() = runTest {
        val local = RecordingLocalCommands(outcome = LocalCommandOutcome.TimedOut("partial"))
        val fixture = CommandsFixture(this, local = local)
        fixture.commands.define(TEST, ScriptCommand("git", timeout = 5.seconds))
        fixture.publish()

        assertEquals(ScriptCommandResult.TimedOut("partial"), fixture.commands.run(TEST, PROJECT))
        local.outcome = LocalCommandOutcome.Invalid
        assertEquals(
            ScriptCommandResult.Refused(ScriptCommandRefusal.InvalidParameter),
            fixture.commands.run(TEST, PROJECT),
        )
        local.outcome = LocalCommandOutcome.NoProject
        assertEquals(ScriptCommandResult.Refused(ScriptCommandRefusal.NoProject), fixture.commands.run(TEST, PROJECT))
    }
}

private class CommandsFixture(
    test: TestScope,
    val local: RecordingLocalCommands = RecordingLocalCommands(),
    toggles: CommandToggles = CommandToggles(),
    scope: HarnessScope = HarnessScope.Attached,
) {
    val access = MutableAccess(test, StandardTestDispatcher(test.testScheduler))
    val commands = commandsTestFactory(local, SavedProjectScopes(setOf(PROJECT, OTHER)), toggles)
        .create(HarnessActivationRequest(harness.copy(scope = scope), code, 1), access)

    fun publish() {
        commands.seal()
        access.isActive = true
    }
}

private class MutableAccess(override val scope: CoroutineScope, override val dispatcher: CoroutineDispatcher) :
    HarnessInstanceAccess {
    override var isActive: Boolean = false
    override val isRegistrationAllowed: Boolean get() = true
    override suspend fun awaitPublication(): Boolean = isActive
}
