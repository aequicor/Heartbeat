package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureFormat
import io.aequicor.heartbeat.feature.computeruse.api.CaptureId
import io.aequicor.heartbeat.feature.computeruse.api.CaptureOwner
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRef
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRegion
import io.aequicor.heartbeat.feature.computeruse.api.CaptureRequest
import io.aequicor.heartbeat.feature.computeruse.api.CaptureResult
import io.aequicor.heartbeat.feature.computeruse.api.CaptureSessionId
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapabilities
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseFailure
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseIntent
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMachineSpec
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseMode
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseOutput
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseState
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseStatus
import io.aequicor.heartbeat.feature.computeruse.api.CropRequest
import io.aequicor.heartbeat.feature.computeruse.api.HostComputerControl
import io.aequicor.heartbeat.feature.computeruse.api.InputAction
import io.aequicor.heartbeat.feature.computeruse.api.InputOutcome
import io.aequicor.heartbeat.feature.computeruse.api.WindowTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerUseAgentToolsTest {
    @Test
    fun `one enabled profile switch exposes every tool without secondary toggles`() = runTest {
        val fixture = Fixture(this, Capturing)
        assertTrue(fixture.tools.specifications(null).any { it.name == "computer_capture" })
        assertTrue(fixture.tools.specifications(null).any { it.name == "computer_type" })
        fixture.preferences.setEnabled(false)
        assertTrue(fixture.tools.specifications(null).isEmpty())
        assertTrue(fixture.tools.instructions(null).isEmpty())
        val reply = fixture.tools.execute(fixture.context, "computer_status", EmptyArguments)
        assertTrue(reply.isError)
        assertEquals("Disabled", reply.text)
    }

    @Test
    fun `authorized input arms the frame automatically without a manual switch`() = runTest {
        val fixture = Fixture(this, Capturing.copy(isInputArmed = false))
        val args = buildJsonObject { put("text", "hello") }
        val approved = fixture.approved("computer_type", args)
        val reply = fixture.tools.execute(approved, "computer_type", args)
        assertFalse(reply.isError)
        assertEquals(
            ComputerUseIntent.Public.ArmInput(true, Session, Frame.id),
            fixture.machine.sent.first(),
        )
        assertEquals(listOf<InputAction>(InputAction.Type("hello")), fixture.machine.appliedActions)
    }

    @Test
    fun `input without dispatcher authorization cannot arm capture`() = runTest {
        val fixture = Fixture(this, Capturing.copy(isInputArmed = false))
        val args = buildJsonObject { put("text", "hello") }
        val reply = fixture.tools.execute(fixture.context, "computer_type", args)
        assertTrue(reply.isError)
        assertTrue(fixture.machine.sent.isEmpty())
        assertTrue(fixture.machine.appliedActions.isEmpty())
    }

    @Test
    fun `facade turn ending releases capture even without a native lifetime`() = runTest {
        val fixture = Fixture(this, Capturing)
        val owner = CaptureOwner.Agent(fixture.context.session, fixture.context.turn)
        fixture.machine.state.value = Capturing.copy(owner = owner)
        val finish = async { fixture.tools.finishTurn(fixture.context.session, fixture.context.turn) }
        runCurrent()
        assertTrue(fixture.machine.state.value is ComputerUseState.Ready)
        assertEquals(ComputerUseIntent.Public.OwnerReleased(owner), fixture.machine.sent.single())
        assertFalse(finish.isCompleted)
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(Session))
        runCurrent()
        finish.await()
    }

    @Test
    fun `finishing an earlier turn preserves the newer owners capture`() = runTest {
        val fixture = Fixture(this, Capturing)
        val newer = Capturing.copy(owner = CaptureOwner.Agent(fixture.context.session, TurnId("newer")))
        fixture.machine.state.value = newer
        fixture.tools.finishTurn(fixture.context.session, fixture.context.turn)
        assertTrue(fixture.machine.sent.isEmpty())
        assertEquals(newer, fixture.machine.state.value)
    }

    @Test
    fun `turn barrier waits for delayed cleanup after native lifetime already ended capture`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val lifetime = Job()
        val context = fixture.context.copy(lifetime = lifetime)
        val arguments = buildJsonObject { put("mode", "desktop") }
        val approved = fixture.approved("computer_capture", arguments, context)
        val capture = async { fixture.tools.execute(approved, "computer_capture", arguments) }
        runCurrent()
        val opened = fixture.machine.state.value as ComputerUseState.Capturing
        fixture.machine.state.value = opened.copy(isOpen = true)
        runCurrent()
        assertFalse(capture.await().isError)
        lifetime.complete()
        runCurrent()
        assertTrue(fixture.machine.state.value is ComputerUseState.Ready)
        val barrier = async { fixture.tools.finishTurn(context.session, context.turn) }
        runCurrent()
        assertFalse(barrier.isCompleted)
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(opened.session))
        runCurrent()
        barrier.await()
    }

    @Test
    fun `cancelling a cleanup barrier keeps the acknowledgement pending for retry`() = runTest {
        val fixture = Fixture(this, Capturing)
        fixture.machine.state.value = Capturing.copy(
            owner = CaptureOwner.Agent(fixture.context.session, fixture.context.turn),
        )
        val first = async { fixture.tools.finishTurn(fixture.context.session, fixture.context.turn) }
        runCurrent()
        assertTrue(fixture.machine.state.value is ComputerUseState.Ready)
        first.cancelAndJoin()
        val retry = async { fixture.tools.finishTurn(fixture.context.session, fixture.context.turn) }
        runCurrent()
        assertFalse(retry.isCompleted)
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(Session))
        runCurrent()
        retry.await()
    }

    @Test
    fun `switching capture keeps prior cleanup acknowledged at the final turn barrier`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val arguments = buildJsonObject { put("mode", "desktop") }
        val sessions = mutableListOf<CaptureSessionId>()
        repeat(2) {
            val approved = fixture.approved("computer_capture", arguments)
            val capture = async { fixture.tools.execute(approved, "computer_capture", arguments) }
            runCurrent()
            val opened = fixture.machine.state.value as ComputerUseState.Capturing
            fixture.machine.state.value = opened.copy(isOpen = true)
            runCurrent()
            assertFalse(capture.await().isError)
            sessions += opened.session
            if (sessions.size == 2) fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(sessions.first()))
        }
        val barrier = async { fixture.tools.finishTurn(fixture.context.session, fixture.context.turn) }
        runCurrent()
        assertFalse(barrier.isCompleted)
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(sessions.last()))
        runCurrent()
        assertTrue(barrier.isCompleted)
        barrier.await()
    }

    @Test
    fun `capture waits for host acknowledgement before requesting a frame`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val args = buildJsonObject { put("mode", "desktop") }
        val spec = fixture.tools.specifications(null).first { it.name == "computer_capture" }
        val approved = fixture.context.copy(authorization = fixture.tools.approval(fixture.context, spec, args))
        val call = async { fixture.tools.execute(approved, spec.name, args) }
        runCurrent()
        assertTrue(fixture.machine.sent.any { it is ComputerUseIntent.Public.BeginCapture })
        assertFalse(fixture.machine.sent.any { it is ComputerUseIntent.Public.Capture })
        val opened = fixture.machine.state.value as ComputerUseState.Capturing
        fixture.machine.state.value = opened.copy(isOpen = true)
        advanceUntilIdle()
        assertFalse(call.await().isError)
        assertEquals(1, fixture.machine.sent.count { it is ComputerUseIntent.Public.Capture })
    }

    @Test
    fun `unrelated frame and refusal cannot answer another request`() = runTest {
        val fixture = Fixture(this, Capturing)
        val reply = fixture.tools.execute(fixture.context, "computer_screenshot", JsonObject(emptyMap()))
        assertFalse(reply.isError)
        assertTrue(reply.text.contains("actual-preview"))
        assertFalse(reply.text.contains("unrelated-preview"))
        val sent = fixture.machine.sent.filterIsInstance<ComputerUseIntent.Public.Capture>().single()
        assertTrue(!sent.requestId.isNullOrEmpty())
    }

    @Test
    fun `approval bound to a previous capture rejects input before sending`() = runTest {
        val fixture = Fixture(this, Capturing)
        val args = buildJsonObject { put("text", "hello") }
        val spec = fixture.tools.specifications(null).first { it.name == "computer_type" }
        val approved = fixture.context.copy(authorization = fixture.tools.approval(fixture.context, spec, args))
        fixture.machine.state.value = Capturing.copy(session = CaptureSessionId("new-session"))
        val reply = fixture.tools.execute(approved, spec.name, args)
        assertTrue(reply.isError)
        assertTrue(reply.text.contains("CaptureChangedSinceApproval"))
        assertTrue(fixture.machine.sent.isEmpty())
    }

    @Test
    fun `timed out screenshot leaves a newer session running`() = runTest {
        val fixture = Fixture(this, Capturing)
        fixture.machine.isReplyEnabled = false
        val call = async { fixture.tools.execute(fixture.context, "computer_screenshot", EmptyArguments) }
        runCurrent()
        val newer = Capturing.copy(session = CaptureSessionId("newer-session"))
        fixture.machine.state.value = newer
        advanceUntilIdle()
        val reply = call.await()
        assertTrue(reply.isError)
        assertEquals(ComputerUseIntent.Public.CancelSession(Session), fixture.machine.sent.last())
        assertEquals(newer, fixture.machine.state.value)
    }

    @Test
    fun `cancelling a waiting screenshot stops the original session`() = runTest {
        val fixture = Fixture(this, Capturing)
        fixture.machine.isReplyEnabled = false
        val call = async { fixture.tools.execute(fixture.context, "computer_screenshot", EmptyArguments) }
        runCurrent()
        call.cancelAndJoin()
        assertEquals(ComputerUseIntent.Public.CancelSession(Session), fixture.machine.sent.last())
        assertEquals(ComputerUseState.Idle, fixture.machine.state.value)
    }

    @Test
    fun `native turn ending during capture opening releases its owner`() = runTest {
        val fixture = Fixture(this, ComputerUseState.Ready(Capabilities))
        val lifetime = Job()
        val context = fixture.context.copy(lifetime = lifetime)
        val arguments = buildJsonObject { put("mode", "desktop") }
        val approved = fixture.approved("computer_capture", arguments, context)
        val call = async { fixture.tools.execute(approved, "computer_capture", arguments) }
        runCurrent()
        val opening = fixture.machine.state.value as ComputerUseState.Capturing
        assertFalse(opening.isOpen)
        lifetime.cancel()
        runCurrent()
        assertEquals(ComputerUseIntent.Public.OwnerReleased(opening.owner), fixture.machine.sent.last())
        assertTrue(fixture.machine.state.value is ComputerUseState.Ready)
        assertFalse(fixture.machine.sent.any { it is ComputerUseIntent.Public.Capture })
        call.cancelAndJoin()
    }

    @Test
    fun `switching capture transfers ownership to the native turn`() = runTest {
        val fixture = Fixture(this, Capturing)
        val lifetime = Job()
        val context = fixture.context.copy(lifetime = lifetime)
        val arguments = buildJsonObject { put("mode", "desktop") }
        val approved = fixture.approved("computer_capture", arguments, context)
        val call = async { fixture.tools.execute(approved, "computer_capture", arguments) }
        runCurrent()
        val opening = fixture.machine.state.value as ComputerUseState.Capturing
        val owner = CaptureOwner.Agent(context.session, context.turn)
        assertEquals(owner, opening.owner)
        assertFalse(opening.isInputArmed)
        fixture.machine.state.value = opening.copy(isOpen = true)
        runCurrent()
        assertFalse(call.await().isError)
        lifetime.cancel()
        runCurrent()
        assertEquals(ComputerUseIntent.Public.OwnerReleased(owner), fixture.machine.sent.last())
        assertTrue(fixture.machine.state.value is ComputerUseState.Ready)
    }

    @Test
    fun `input retains the approved frame when the session changes after the gate`() = runTest {
        val fixture = Fixture(this, Capturing)
        val arguments = buildJsonObject { put("text", "hello") }
        val approved = fixture.approved("computer_type", arguments)
        val newer = Capturing.copy(
            session = CaptureSessionId("newer-session"),
            lastPreview = Frame.copy(id = CaptureId("newer-preview")),
        )
        fixture.registry.findCount = 0
        fixture.registry.onFind = { count ->
            if (count == 2) fixture.machine.state.value = newer
        }
        val reply = fixture.tools.execute(approved, "computer_type", arguments)
        assertTrue(reply.isError)
        val arming = fixture.machine.sent.filterIsInstance<ComputerUseIntent.Public.ArmInput>().single()
        assertEquals(Session, arming.expectedSession)
        assertEquals(Frame.id, arming.expectedCapture)
        assertTrue(fixture.machine.sent.none { it is ComputerUseIntent.Public.Input })
        assertTrue(fixture.machine.appliedActions.isEmpty())
        assertEquals(newer, fixture.machine.state.value)
    }

    @Test
    fun `frame requests cannot move to a session replaced before delivery`() = runTest {
        for (tool in listOf("computer_screenshot", "computer_zoom")) {
            val fixture = Fixture(this, Capturing)
            val newer = Capturing.copy(session = CaptureSessionId("newer-session"))
            fixture.machine.beforeSend = { intent ->
                if (intent is ComputerUseIntent.Public.Capture || intent is ComputerUseIntent.Public.Crop) {
                    fixture.machine.state.value = newer
                }
            }
            val reply = fixture.tools.execute(fixture.context, tool, EmptyArguments)
            assertTrue(reply.isError, tool)
            assertEquals(newer, fixture.machine.state.value, tool)
            when (val operation = fixture.machine.sent.single()) {
                is ComputerUseIntent.Public.Capture -> assertEquals(Session, operation.expectedSession)
                is ComputerUseIntent.Public.Crop -> assertEquals(Session, operation.expectedSession)
                else -> error("Expected a frame request")
            }
        }
    }

    @Test
    fun `type approval exposes newline tab backspace and literal backslash`() = runTest {
        val fixture = Fixture(this, Capturing)
        val text = "run\n\t\b\\done"
        val arguments = buildJsonObject { put("text", text) }
        val spec = fixture.tools.specifications(null).first { it.name == "computer_type" }
        val approval = fixture.tools.approval(fixture.context, spec, arguments)
        assertEquals("Type ${text.length} characters: run\\n\\t\\b\\\\done", approval.title)
        assertFalse(approval.title.any { it.code < 32 })
    }

    @Test
    fun `long type approval reports the full length and visible truncation`() = runTest {
        val fixture = Fixture(this, Capturing)
        val arguments = buildJsonObject { put("text", "x".repeat(700)) }
        val spec = fixture.tools.specifications(null).first { it.name == "computer_type" }
        val approval = fixture.tools.approval(fixture.context, spec, arguments)
        assertTrue(approval.title.startsWith("Type 700 characters:"))
        assertTrue(approval.title.endsWith("(preview truncated)"))
        assertFalse(approval.title.contains("x".repeat(700)))
    }

    @Test
    fun `malformed screenshot geometry never falls back to a full frame`() = runTest {
        val cases = listOf(
            buildJsonObject { put("tile", "broken") },
            buildJsonObject { put("regionX", 5) },
            buildJsonObject {
                put("regionX", 0)
                put("regionY", 0)
                put("regionWidth", 0)
                put("regionHeight", 10)
            },
        )
        for (arguments in cases) {
            val fixture = Fixture(this, Capturing)
            val reply = fixture.tools.execute(fixture.context, "computer_screenshot", arguments)
            assertTrue(reply.isError)
            assertTrue(fixture.machine.sent.isEmpty())
        }
    }

    @Test
    fun `invalid zoom geometry cannot crop the entire master instead`() = runTest {
        val cases = listOf(
            buildJsonObject { put("tile", "broken") },
            buildJsonObject { put("tile", "2:2") },
            buildJsonObject {
                put("nx", 0.0)
                put("ny", 0.0)
                put("nw", 2.0)
                put("nh", 1.0)
            },
            buildJsonObject { put("scale", 0.0) },
        )
        for (arguments in cases) {
            val fixture = Fixture(this, Capturing)
            val reply = fixture.tools.execute(fixture.context, "computer_zoom", arguments)
            assertTrue(reply.isError)
            assertTrue(fixture.machine.sent.isEmpty())
        }
    }

    @Test
    fun `zoom tile uses the stored native master and preserves its session`() = runTest {
        val master = Frame.copy(
            id = CaptureId("native-master"),
            region = CaptureRegion(0, 0, 2200, 1200),
            masterWidthPx = 2200,
            masterHeightPx = 1200,
        )
        val fixture = Fixture(this, Capturing.copy(master = master))
        val arguments = buildJsonObject { put("tile", "1:1") }
        val spec = fixture.tools.specifications(null).first { it.name == "computer_zoom" }
        assertTrue("tile" in spec.inputSchema.getValue("properties").jsonObject)
        val reply = fixture.tools.execute(fixture.context, spec.name, arguments)
        assertFalse(reply.isError)
        val crop = fixture.machine.sent.filterIsInstance<ComputerUseIntent.Public.Crop>().single()
        assertEquals(master.id, crop.request.capture)
        assertEquals(CaptureRegion(960, 960, 1024, 240), crop.request.region)
        assertEquals(Session, crop.expectedSession)
    }

    @Test
    fun `release waits for cleanup of the approved session`() = runTest {
        val fixture = Fixture(this, Capturing)
        val approved = fixture.approved("computer_release", EmptyArguments)
        val call = async { fixture.tools.execute(approved, "computer_release", EmptyArguments) }
        runCurrent()
        assertEquals(ComputerUseIntent.Public.CancelSession(Session), fixture.machine.sent.single())
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(CaptureSessionId("unrelated-session")))
        runCurrent()
        assertFalse(call.isCompleted)
        fixture.machine.outputs.emit(ComputerUseOutput.SessionClosed(Session))
        runCurrent()
        assertFalse(call.await().isError)
    }

    @Test
    fun `another turn can neither use switch nor release the capture`() = runTest {
        val foreign = Capturing.copy(owner = CaptureOwner.Agent(Owner.session, TurnId("other-turn")))
        val fixture = Fixture(this, foreign)
        val text = buildJsonObject { put("text", "hello") }
        val desktop = buildJsonObject { put("mode", "desktop") }
        val calls = listOf(
            "computer_screenshot" to fixture.context,
            "computer_zoom" to fixture.context,
            "computer_type" to fixture.approved("computer_type", text),
            "computer_capture" to fixture.approved("computer_capture", desktop),
            "computer_release" to fixture.approved("computer_release", EmptyArguments),
        )
        for ((tool, context) in calls) {
            val arguments = when (tool) {
                "computer_type" -> text
                "computer_capture" -> desktop
                else -> EmptyArguments
            }
            val reply = fixture.tools.execute(context, tool, arguments)
            assertTrue(reply.isError, tool)
            assertEquals("CaptureOwnedByAnotherTurn", reply.text, tool)
        }
        assertTrue(fixture.machine.sent.isEmpty())
        assertEquals(foreign, fixture.machine.state.value)
        assertFalse(fixture.tools.execute(fixture.context, "computer_status", EmptyArguments).isError)
    }

    @Test
    fun `parallel approvals stay valid after the first input arms the frame`() = runTest {
        val fixture = Fixture(this, Capturing.copy(isInputArmed = false))
        val args = buildJsonObject { put("text", "hello") }
        val first = fixture.approved("computer_type", args)
        val second = fixture.approved("computer_type", args)
        assertFalse(fixture.tools.execute(first, "computer_type", args).isError)
        assertFalse(fixture.tools.execute(second, "computer_type", args).isError)
        assertEquals(2, fixture.machine.appliedActions.size)
    }

    @Test
    fun `input without an operating system grant reports the lost permission`() = runTest {
        val noInput = Capabilities.copy(isInputAvailable = false)
        val fixture = Fixture(this, Capturing.copy(capabilities = noInput, isInputArmed = false))
        val args = buildJsonObject { put("text", "hello") }
        val reply = fixture.tools.execute(fixture.approved("computer_type", args), "computer_type", args)
        assertTrue(reply.isError)
        assertEquals("PermissionLost", reply.text)
        assertTrue(fixture.machine.appliedActions.isEmpty())
    }

    @Test
    fun `lost cleanup acknowledgement ends the turn barrier without failing or leaking`() = runTest {
        val fixture = Fixture(this, Capturing)
        val barrier = async { fixture.tools.finishTurn(fixture.context.session, fixture.context.turn) }
        runCurrent()
        assertTrue(fixture.machine.state.value is ComputerUseState.Ready)
        assertFalse(barrier.isCompleted)
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(barrier.isCompleted)
        barrier.await()
        assertEquals(0, fixture.machine.outputs.subscriptionCount.value)
        val again = async { fixture.tools.finishTurn(fixture.context.session, fixture.context.turn) }
        runCurrent()
        assertTrue(again.isCompleted)
    }

    private class Fixture(scope: TestScope, initial: ComputerUseState) {
        val machine = ToolMachine(initial)
        val registry = ToolRegistry(machine)
        val preferences = FakeComputerUsePreferences()
        val tools = ComputerUseAgentTools(
            registry,
            FakeToggles(mapOf(ComputerUseEnabled.key to true)),
            object : HostComputerControl {
                override suspend fun status() = ComputerUseStatus(Capabilities)
                override suspend fun windows() = emptyList<WindowTarget>()
                override suspend fun capture(request: CaptureRequest) = CaptureResult()
                override suspend fun crop(request: CropRequest) = CaptureResult()
                override suspend fun input(action: InputAction) = InputOutcome.Applied
                override suspend fun revoke() = Unit
            },
            ComputerUseCaptureLifecycle(registry, TestComputerUseScope(scope.backgroundScope)),
            preferences,
        )
        val context = AgentToolContext(Owner.session, null, Owner.turn)

        suspend fun approved(
            name: String,
            arguments: JsonObject,
            context: AgentToolContext = this.context,
        ): AgentToolContext {
            val spec = tools.specifications(null).first { it.name == name }
            return context.copy(authorization = tools.approval(context, spec, arguments))
        }
    }

    private class ToolMachine(initial: ComputerUseState) :
        MachineRef<ComputerUseState, ComputerUseIntent.Public, ComputerUseOutput> {
        override val name = "computer-use"
        override val state = MutableStateFlow(initial)
        override val outputs = MutableSharedFlow<ComputerUseOutput>()
        val sent = mutableListOf<ComputerUseIntent.Public>()
        val appliedActions = mutableListOf<InputAction>()
        var isReplyEnabled = true
        var beforeSend: ((ComputerUseIntent.Public) -> Unit)? = null
        override suspend fun send(intent: ComputerUseIntent.Public): SendResult {
            sent += intent
            beforeSend?.invoke(intent)
            if (!apply(intent)) return SendResult.Ignored
            if (intent is ComputerUseIntent.Public.Capture && isReplyEnabled) {
                outputs.emit(ComputerUseOutput.Rejected(ComputerUseFailure.CaptureFailed, "unrelated"))
                outputs.emit(
                    ComputerUseOutput.FrameReady(
                        Frame.copy(id = CaptureId("unrelated-preview")),
                        requestId = "unrelated",
                    ),
                )
                val frame = Frame.copy(session = (state.value as ComputerUseState.Capturing).session)
                apply(ComputerUseIntent.Internal.FrameCaptured(frame, frame, requestId = intent.requestId))
            }
            if (intent is ComputerUseIntent.Public.Crop && isReplyEnabled) {
                apply(ComputerUseIntent.Internal.CropProduced(Frame, intent.requestId))
            }
            if (intent is ComputerUseIntent.Public.Input && isReplyEnabled) {
                appliedActions += intent.action
                apply(ComputerUseIntent.Internal.InputApplied(intent.action, intent.requestId))
            }
            return SendResult.Accepted
        }

        private suspend fun apply(intent: ComputerUseIntent): Boolean {
            val resolution = ComputerUseMachineSpec.resolve(state.value, intent) ?: return false
            state.value = resolution.to
            resolution.outputs.forEach { outputs.emit(it) }
            return true
        }
    }

    private class ToolRegistry(private val machine: ToolMachine) : MachineRegistry {
        var findCount = 0
        var onFind: ((Int) -> Unit)? = null

        @Suppress("UNCHECKED_CAST") // This fixture registers exactly the computer use machine key.
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
            key: MachineKey<S, I, P, E, O>,
        ): MachineRef<S, P, O>? {
            findCount += 1
            onFind?.invoke(findCount)
            return machine as MachineRef<S, P, O>
        }
        override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
            key: MachineKey<S, I, P, E, O>,
        ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(
            find(key),
        )
        override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
            key: MachineKey<S, I, P, E, O>,
            intent: P,
        ): SendResult = find(
            key,
        )!!.send(intent)
    }

    private companion object {
        val Capabilities = ComputerUseCapabilities(true, true, true, true)
        val Session = CaptureSessionId("session")
        val EmptyArguments = JsonObject(emptyMap())
        val Frame = CaptureRef(
            CaptureId(
                "actual-preview",
            ),
            Session, CaptureFormat.Png, 20, 10, CaptureRegion(0, 0, 20, 10), 20, 10, 100, 1, "/test/frame.png", 1,
        )
        val Owner = CaptureOwner.Agent(SessionRef(EngineId("pi"), SessionSourceId("local"), "session"), TurnId("turn"))
        val Capturing = ComputerUseState.Capturing(
            Session,
            ComputerUseMode.Desktop(),
            Owner,
            Capabilities,
            isInputArmed = true,
            master = Frame,
            lastPreview = Frame,
            isOpen = true,
        )
    }
}
