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
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MachineStudioHarnessesTest {
    private val session = SessionRef(EngineId("test"), SessionSourceId("local"), "native")
    private val other = SessionRef(EngineId("test"), SessionSourceId("local"), "other")

    @Test
    fun `only a globally disabled feature defers reading carried connections`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val disabled = object : FeatureToggles by HarnessToggleOn {
            @Suppress("UNCHECKED_CAST") // This test reads only the Boolean harness toggle.
            override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = false as T
        }
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), disabled)
        assertNull(harnesses.attached(session))
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `outside carry restores every committed harness including disabled ones`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val ready = library.state.value as HarnessState.Ready
        library.state.value = ready.copy(
            harnesses = ready.harnesses.map { it.copy(harness = it.harness.copy(isEnabled = false)) },
            attachments = mapOf(session to setOf(HarnessId("compose"), HarnessId("verify"))),
        )
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        val selected = checkNotNull(harnesses.attached(session))
        assertEquals(setOf("compose", "verify"), selected)

        harnesses.restore(selected, other)
        assertEquals(library.attached(session), library.attached(other))
        library.sent.clear()
        harnesses.restore(selected, other)
        assertTrue(library.sent.isEmpty())
    }

    @Test
    fun `outside carry fails on unavailable state and retries unconfirmed attachment`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val ready = library.state.value as HarnessState.Ready
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        library.state.value = ready.copy(isSuspended = true)
        assertFailsWith<IllegalStateException> { harnesses.attached(session) }
        library.state.value = HarnessState.Failed()
        assertFailsWith<IllegalStateException> { harnesses.attached(session) }
        library.state.value = ready
        library.isSilent = true
        assertFailsWith<IllegalStateException> { harnesses.restore(setOf("compose"), other) }
        assertTrue(library.attached(other).isEmpty())

        library.isSilent = false
        harnesses.restore(setOf("compose"), other)
        assertEquals(setOf(HarnessId("compose")), library.attached(other))
    }

    @Test
    fun `clearing eight connected harnesses waits for each persisted disconnection`() = runTest {
        val names = (1..8).map { "harness_$it" }
        val library = SpecHarnessLibrary(backgroundScope, names, writeMillis = 100)
        library.state.value = (library.state.value as HarnessState.Ready).copy(
            attachments = mapOf(session to names.map(::HarnessId).toSet()),
        )
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        val results = names.map { name -> async { harnesses.connect(session, name, false) } }.awaitAll()
        assertTrue(results.all { it })
        assertTrue(library.attached(session).isEmpty())
        assertEquals(8, library.sent.size, "bulk clear should not race its own attachment writes")
    }

    @Test
    fun `the first turn of a submission connects every claimed harness one write after another`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("submission", setOf("compose", "verify"))
        assertEquals(mapOf("harness" to setOf("compose", "verify")), harnesses.preselected("submission"))
        assertEquals(emptyMap(), harnesses.preselected("other"))

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
    fun `a library that leaves a write unanswered gets no further writes for the first prompt`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        library.isSilent = true
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("submission", setOf("compose", "verify"))

        harnesses.beforeSubmit("submission", session)

        assertEquals(1, library.sent.size)
        assertEquals(3_000L, currentTime, "one confirmation wait, not one per harness")
    }

    @Test
    fun `the first prompt waits for its connections only up to a common deadline`() = runTest {
        val names = listOf("alpha", "bravo", "charlie", "delta", "echo")
        val library = SpecHarnessLibrary(backgroundScope, names, writeMillis = 2_400L)
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)
        harnesses.claim("submission", names.toSet())

        harnesses.beforeSubmit("submission", session)

        assertEquals(10_000L, currentTime)
        assertEquals(4, library.attached(session).size)
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

    @Test
    fun `legacy scopes are offered as ordinary choices and never count as connected`() = runTest {
        val library = SpecHarnessLibrary(backgroundScope)
        val profile = libraryEntry("compose").let { it.copy(harness = it.harness.copy(scope = HarnessScope.Profile)) }
        val projects = libraryEntry("verify").let {
            it.copy(harness = it.harness.copy(scope = HarnessScope.Projects(setOf(WorkspaceRef("project")))))
        }
        library.state.value = HarnessState.Ready(
            harnesses = listOf(profile, projects),
            attachments = mapOf(other to setOf(HarnessId("verify"))),
        )
        val harnesses = MachineStudioHarnesses(HarnessRegistry(library), HarnessToggleOn)

        val choices = harnesses.choices.first()

        assertEquals(listOf("compose", "verify"), choices.harnesses.map { it.id })
        assertTrue(choices.harnesses.first().projectIds.isEmpty())
        assertEquals(setOf("project"), choices.harnesses.last().projectIds)
        assertEquals(mapOf(other to setOf("verify")), choices.attached)
    }
}

/**
 * Runs the actual harness spec. Attachment writes finish later, as the repository does, so the library is busy
 * while one is in flight.
 */
private class SpecHarnessLibrary(
    private val scope: CoroutineScope,
    names: List<String> = listOf("compose", "verify"),
    private val writeMillis: Long = WRITE_MILLIS,
) : MachineRef<HarnessState, HarnessIntent.Public, HarnessOutput> {
    override val name: String = HarnessMachineKey.name
    override val state = MutableStateFlow<HarnessState>(HarnessState.Ready(harnesses = names.map(::libraryEntry)))
    override val outputs = MutableSharedFlow<HarnessOutput>(extraBufferCapacity = 16)
    val sent = mutableListOf<HarnessIntent.Public>()

    /** Takes every intent and never answers, as a stalled library does. */
    var isSilent = false
    private val lock = Mutex()

    fun attached(session: SessionRef): Set<HarnessId> =
        (state.value as HarnessState.Ready).attachments[session].orEmpty()

    override suspend fun send(intent: HarnessIntent.Public): SendResult {
        sent += intent
        return if (isSilent) SendResult.Accepted else apply(intent)
    }

    private suspend fun apply(intent: HarnessIntent): SendResult = lock.withLock {
        val next = HarnessMachineSpec.resolve(state.value, intent) ?: return@withLock SendResult.Ignored
        state.value = next.to
        next.outputs.forEach { outputs.emit(it) }
        next.effects.filterIsInstance<HarnessEffect.SaveAttachments>().forEach { effect ->
            scope.launch {
                delay(writeMillis)
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
