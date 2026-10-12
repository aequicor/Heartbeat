package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioConfiguredSubmissionTest {
    @Test
    fun `a compaction keeps the chat's trust, which the engine then reports back unchanged`() = runTest {
        val fixture = SubmissionFixture(this)

        fixture.submit("compact-1", compaction = ContextCompaction())
        runCurrent()

        assertTrue(fixture.session.requests.single().compaction != null)
        assertEquals(TrustLevel.AutoEdits, fixture.session.requests.single().trust)
        assertEquals(ApprovalMode.AutoEdits, fixture.storedApproval())
    }

    @Test
    fun `the handoff summary turn asks, but its trust never becomes the chat's configuration`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.submit("prompt")
        runCurrent()

        fixture.submit("${HANDOFF_REQUEST_PREFIX}1")
        runCurrent()

        assertEquals(TrustLevel.Ask, fixture.session.requests.last().trust)
        assertEquals(TrustLevel.Ask, fixture.native.configuration.value.trust)
        assertEquals(ApprovalMode.AutoEdits, fixture.storedApproval())
        fixture.submit("next")
        runCurrent()
        assertEquals(TrustLevel.AutoEdits, fixture.session.requests.last().trust)
        // A turn of the chat itself in the same session reflects its configuration again.
        fixture.native.configuration.value = fixture.native.configuration.value.copy(reasoningEffort = "high")
        runCurrent()
        assertEquals("high", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
    }

    @Test
    fun `changing the effort during the handoff summary turn keeps the chat's trust`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.access.otherSessions["chat"] = fixture.session
        fixture.submit("prompt")
        runCurrent()
        fixture.submit("${HANDOFF_REQUEST_PREFIX}1")
        runCurrent()

        fixture.configuration.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()

        assertEquals(ApprovalMode.AutoEdits, fixture.storedApproval())
        assertEquals("high", fixture.access.records.getValue("chat").configuration?.reasoningEffort)
        assertEquals(ApprovalMode.AutoEdits, fixture.access.states.getValue("chat").applied?.approval)
    }

    @Test
    fun `the carried seed reaches the turns until one marks it delivered, even when marking fails`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(pendingSeed = "the essence")
        fixture.access.seedFailure = IllegalStateException("store unavailable")

        assertEquals(TurnId("turn-1"), fixture.submit("first"))
        fixture.access.seedFailure = null
        fixture.submit("second")
        fixture.submit("third")

        assertEquals(listOf(true, true, false), fixture.session.requests.map { "the essence" in it.text() })
        assertEquals(1, fixture.access.consumedSeeds)
    }

    @Test
    fun `a compaction neither carries nor consumes the seed`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(pendingSeed = "the essence")

        fixture.submit("compact-1", compaction = ContextCompaction())

        assertFalse("the essence" in fixture.session.requests.single().text())
        assertEquals(0, fixture.access.consumedSeeds)
    }

    private fun PromptRequest.text(): String = parts.filterIsInstance<ContentPart.Text>().joinToString { it.text }
}

private class SubmissionFixture(test: TestScope) {
    val configuration = StudioConfigurationFixture(test)
    private val target: EngineTarget = configuration.target
    val access = configuration.access
    val native = ConfigurationNative(configuration.initial.copy(trust = TrustLevel.AutoEdits))
    val session = SubmissionSession(target, native)
    private val submission = StudioConfiguredSubmission(
        configuration.controller,
        StudioLearningPrompts(LearningOff, UnconfinedDispatchers),
        StudioHelperPolicy(ChecklistTestStores()),
        object : EffortChoicesView {
            override val state = MutableStateFlow<EffortConfigurationState>(EffortConfigurationState.Ready())
        },
        StudioRequestOrigins(lazyOf(emptySet())),
    )

    init {
        access.records["chat"] = StudioChatRecord(
            "chat",
            "Chat",
            Instant.fromEpochSeconds(0),
            ref = session.ref,
            target = target,
            configuration = native.configuration.value.studio(target),
        )
    }

    suspend fun submit(request: String, compaction: ContextCompaction? = null): TurnId = submission.submit(
        access,
        session,
        target,
        StudioTurnRequest(
            "chat",
            prompt = if (compaction == null) "Hello" else "",
            settings = DefaultRunSettings,
            kind = WorktreeRunKind.Coding,
            request = RequestId(request),
            compaction = compaction,
        ),
        { listOf(StudioModel(target.studioModelId(), "Model", isTrustSupported = true)) },
        { access.states },
    )

    fun storedApproval(): ApprovalMode? = access.records.getValue("chat").configuration?.approval
}

/** Publishes the trust of every accepted turn as its configuration, the way Pi does after acceptance. */
private class SubmissionSession(target: EngineTarget, private val native: ConfigurationNative) : ActiveSession {
    val requests = mutableListOf<PromptRequest>()
    override val ref = SessionRef(target.engine, SessionSourceId("source"), "native")
    override val route = ExecutionRoute(target.engine, target.binding, AuthSourceId("auth"), AuthRevision.Known("1"))
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    private val prompts = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            requests += request
            native.configuration.value = native.configuration.value.copy(trust = request.trust ?: TrustLevel.Ask)
            return TurnId("turn-${requests.size}")
        }
    }
    override val features = object : EngineFeatures {
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            listOf(prompts, native).firstNotNullOfOrNull { key.type.safeCast(it) }
                ?.let { FeatureAccess.Available(it) } ?: FeatureAccess.Unsupported
    }

    override suspend fun close() {
        state.value = ActiveSessionState.Closed
    }
}

private object UnconfinedDispatchers : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
}

private object LearningOff : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // Only the learning flag is read, and it is off.
    override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(false as T)

    @Suppress("UNCHECKED_CAST") // Only the learning flag is read, and it is off.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = false as T
}
