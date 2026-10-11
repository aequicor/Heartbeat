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
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEffect
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class MachineStudioHarnessesTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val other = SessionRef(EngineId("test"), SessionSourceId("local"), "other")

    @Test
    fun `the first turn of a submission connects every claimed harness one write after another`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("submission", setOf("compose", "verify"))

        harnesses.beforeSubmit("submission", session)

        assertEquals(setOf(HarnessId("compose"), HarnessId("verify")), library.attached(session))
        library.sent.clear()
        harnesses.beforeSubmit("submission", session)
        assertTrue(library.sent.isEmpty(), "the claim is used once")
    }

    @Test
    fun `turns of other submissions never wait and leave the claim to its own turn`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("submission", setOf("compose"))

        harnesses.beforeSubmit("", other)
        harnesses.beforeSubmit("another", other)
        assertEquals(0L, currentTime)
        assertTrue(library.sent.isEmpty())

        harnesses.beforeSubmit("submission", session)
        assertEquals(setOf(HarnessId("compose")), library.attached(session))
    }

    @Test
    fun `new chats submitted together are all connected although the library writes one at a time`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("first", setOf("compose", "verify"))
        harnesses.claim("second", setOf("compose"))

        listOf(
            async { harnesses.beforeSubmit("first", session) },
            async { harnesses.beforeSubmit("second", other) },
        ).awaitAll()

        assertEquals(setOf(HarnessId("compose"), HarnessId("verify")), library.attached(session))
        assertEquals(setOf(HarnessId("compose")), library.attached(other))
    }

    @Test
    fun `claims that never reach a turn are released or evicted by newer ones`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("released", setOf("compose"))
        harnesses.release("released")
        (0..16).forEach { harnesses.claim("submission-$it", setOf("compose")) }

        harnesses.beforeSubmit("released", session)
        harnesses.beforeSubmit("submission-0", session)
        assertTrue(library.sent.isEmpty())

        harnesses.beforeSubmit("submission-16", session)
        assertEquals(setOf(HarnessId("compose")), library.attached(session))
    }

    @Test
    fun `an existing chat reports whether the library confirmed the connection`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)

        assertTrue(harnesses.connect(session, "compose", isSelected = true))
        assertFalse(harnesses.connect(session, "missing", isSelected = true))
        assertTrue(harnesses.connect(session, "compose", isSelected = false))
        assertTrue(library.sent.last() is HarnessIntent.Public.Detach)
        assertTrue(library.attached(session).isEmpty())
    }
}

/**
 * Runs the actual harness spec. Attachment writes finish later, as the repository does, so the library is busy
 * while one is in flight.
 */
private class SpecHarnessLibrary(private val scope: CoroutineScope) :
    MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput> {
    override val name: String = HarnessMachineKey.name
    override val state = MutableStateFlow<HarnessState>(
        HarnessState.Ready(harnesses = listOf("compose", "verify").map(::libraryEntry)),
    )
    override val outputs = MutableSharedFlow<HarnessOutput>(extraBufferCapacity = 16)
    val sent = mutableListOf<HarnessIntent.Public>()
    private val lock = Mutex()

    fun attached(session: SessionRef): Set<HarnessId> =
        (state.value as HarnessState.Ready).attachments[session].orEmpty()

    override suspend fun send(intent: HarnessIntent.Public): SendResult {
        sent += intent
        return apply(intent)
    }

    private suspend fun apply(intent: HarnessIntent): SendResult = lock.withLock {
        val next = HarnessMachineSpec.resolve(state.value, intent) ?: return@withLock SendResult.Ignored
        state.value = next.to
        next.outputs.forEach { outputs.emit(it) }
        next.effects.filterIsInstance<HarnessEffect.SaveAttachments>().forEach { effect ->
            scope.launch {
                delay(WRITE_MILLIS)
                apply(HarnessIntent.Internal.AttachmentsSaved(effect.write))
            }
        }
        SendResult.Accepted
    }
}

private fun libraryEntry(name: String) = HarnessEntry(
    Harness(
        HarnessId(name), HarnessName(name), name, "", HarnessScope.Attached, true, emptyList(), ToolPolicySpec(),
        null, 0, Epoch, Epoch,
    ),
)

private class HarnessRegistry(private val library: MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput>) :
    MachineRegistry {
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

private val Epoch = Instant.fromEpochMilliseconds(0)
private const val WRITE_MILLIS = 50L
