package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineEffect
import io.aequicor.heartbeat.core.statemachine.MachineIntent
import io.aequicor.heartbeat.core.statemachine.MachineKey
import io.aequicor.heartbeat.core.statemachine.MachineOutput
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.MachineState
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MachineStudioHarnessesTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")

    @Test
    fun `a claimed choice connects the created chat before its first prompt`() = runTest {
        val library = FakeHarnessLibrary()
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("submission", setOf("compose", "verify"))

        val turn = async { harnesses.beforeSubmit("chat", session) }
        runCurrent()
        assertTrue(library.sent.isEmpty(), "the turn waits until the submission names its chat")
        harnesses.bindSubmission("submission", "chat")
        turn.await()

        val attached = library.sent.filterIsInstance<HarnessIntent.Public.Attach>()
        assertEquals(setOf("compose", "verify"), attached.map { it.id.value }.toSet())
        assertTrue(attached.all { it.session == session })
        library.sent.clear()
        harnesses.beforeSubmit("chat", session)
        assertTrue(library.sent.isEmpty(), "the claim is used once")
    }

    @Test
    fun `turns of other chats do not wait without a claim in flight`() = runTest {
        val library = FakeHarnessLibrary()
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("released", setOf("compose"))
        harnesses.release("released")

        harnesses.beforeSubmit("chat", session)

        assertEquals(0L, currentTime)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `an existing chat reports whether the library confirmed the connection`() = runTest {
        val library = FakeHarnessLibrary()
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)

        assertTrue(harnesses.connect(session, "compose", isSelected = true))
        library.rejection = HarnessRejection.Conflict
        assertFalse(harnesses.connect(session, "compose", isSelected = false))
        assertTrue(library.sent.last() is HarnessIntent.Public.Detach)
    }
}

/** Answers every attach or detach at once, or rejects it when [rejection] is set. */
private class FakeHarnessLibrary : MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput> {
    override val name: String = HarnessMachineKey.name
    override val state = MutableStateFlow<HarnessState>(HarnessState.Ready())
    override val outputs = MutableSharedFlow<HarnessOutput>(extraBufferCapacity = 8)
    val sent = mutableListOf<HarnessIntent.Public>()
    var rejection: HarnessRejection? = null

    override suspend fun send(intent: HarnessIntent.Public): SendResult {
        sent += intent
        val rejected = rejection?.let { HarnessOutput.Rejected(intent.requestId, it) }
        val output = rejected ?: when (intent) {
            is HarnessIntent.Public.Attach -> HarnessOutput.Attached(intent.requestId, intent.id, intent.session)
            is HarnessIntent.Public.Detach -> HarnessOutput.Detached(intent.requestId, intent.id, intent.session)
            else -> error("unexpected ${intent::class.simpleName}")
        }
        outputs.tryEmit(output)
        return SendResult.Accepted
    }
}

private class HarnessRegistry(private val library: FakeHarnessLibrary) : MachineRegistry {
    @Suppress("UNCHECKED_CAST") // The fake resolves only the harness key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == HarnessMachineKey) library as MachineRef<S, P, O> else null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult = SendResult.NotRunning
}

private object HarnessToggleOn : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Only the Boolean harness flag is read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> {
        check(toggle == HarnessEnabled) { "unexpected toggle ${toggle.key}" }
        return flowOf(true) as Flow<T>
    }

    @Suppress("UNCHECKED_CAST") // Only the Boolean harness flag is read.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T {
        check(toggle == HarnessEnabled) { "unexpected toggle ${toggle.key}" }
        return true as T
    }
}
