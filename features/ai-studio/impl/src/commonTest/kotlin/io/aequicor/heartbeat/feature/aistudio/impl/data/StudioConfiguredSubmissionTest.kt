package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityPolicy
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
    fun `automatic safety stays the default for ordinary user prompts`() = runTest {
        val fixture = SubmissionFixture(this)

        fixture.submit("ordinary")

        val request = fixture.session.requests.single()
        assertEquals(AgentToolDecisionMode.AutomaticSafety, request.decisionMode)
        assertEquals(TrustLevel.AutoEdits, request.trust)
        assertEquals(1, fixture.securityReads)
    }

    @Test
    fun `each user prompt snapshots the current profile level instead of saved chat trust`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.security.value = StudioSecurityLevel.Ask
        fixture.submit("ask")
        runCurrent()
        fixture.security.value = StudioSecurityLevel.Trust
        fixture.submit("trust")
        runCurrent()
        fixture.security.value = StudioSecurityLevel.Automatic
        fixture.submit("review")

        assertEquals(
            listOf(TrustLevel.Ask, TrustLevel.Full, TrustLevel.Full),
            fixture.session.requests.map { it.trust },
        )
        assertEquals(
            listOf(
                AgentToolDecisionMode.Interactive,
                AgentToolDecisionMode.Interactive,
                AgentToolDecisionMode.AutomaticSafety,
            ),
            fixture.session.requests.map { it.decisionMode },
        )
        assertEquals(3, fixture.securityReads)
    }

    @Test
    fun `interactive profile levels leave trust unset for engines without native trust support`() = runTest {
        val fixture = SubmissionFixture(this, isTrustSupported = false)
        fixture.security.value = StudioSecurityLevel.Ask
        fixture.submit("unsupported-ask")
        fixture.security.value = StudioSecurityLevel.Trust
        fixture.submit("unsupported-trust")

        assertEquals(listOf(null, null), fixture.session.requests.map { it.trust })
        assertTrue(fixture.session.requests.all { it.decisionMode == AgentToolDecisionMode.Interactive })
    }

    @Test
    fun `full profile trust does not replace host jobs or compaction or handoff policy`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.security.value = StudioSecurityLevel.Trust

        fixture.submit("scheduled", isUserPrompt = false)
        fixture.submit("compact", compaction = ContextCompaction())
        fixture.submit("${HANDOFF_REQUEST_PREFIX}1")

        assertEquals(
            listOf(TrustLevel.AutoEdits, TrustLevel.AutoEdits, TrustLevel.Ask),
            fixture.session.requests.map { it.trust },
        )
        assertTrue(fixture.session.requests.all { it.decisionMode == AgentToolDecisionMode.AutomaticSafety })
        assertEquals(0, fixture.securityReads)
    }

    @Test
    fun `full profile trust never raises the ceiling of a helper user prompt`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.security.value = StudioSecurityLevel.Trust
        fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(
            helper = StudioHelperIdentity(ActionId("owner"), null, null, TrustLevel.Ask),
        )

        fixture.submit("helper-user-message")

        val request = fixture.session.requests.single()
        assertEquals(TrustLevel.Ask, request.trust)
        assertEquals(AgentToolDecisionMode.AutomaticSafety, request.decisionMode)
        assertEquals(0, fixture.securityReads)
    }

    @Test
    fun `helper turns retain parent review and automatic policy under full profile trust`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.security.value = StudioSecurityLevel.Trust
        val parent = fixture.configuration.target.copy(model = ModelId("parent"))
        fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(
            helper = StudioHelperIdentity(
                ActionId("owner"),
                null,
                null,
                TrustLevel.Ask,
                HelperModelOptions(reviewTarget = parent),
            ),
        )

        fixture.submit("helper-user-message")
        fixture.submit("helper-host-message", isUserPrompt = false)
        fixture.submit("helper-compact", compaction = ContextCompaction())

        assertEquals(3, fixture.session.requests.size)
        fixture.session.requests.forEach { request ->
            assertEquals(parent, request.reviewTarget)
            assertEquals(AgentToolDecisionMode.AutomaticSafety, request.decisionMode)
            assertEquals(TrustLevel.Ask, request.trust)
        }
        assertEquals(0, fixture.securityReads)
    }

    @Test
    fun `a compaction keeps the chat's trust which the engine then reports back unchanged`() = runTest {
        val fixture = SubmissionFixture(this)

        fixture.submit("compact-1", compaction = ContextCompaction())
        runCurrent()

        assertTrue(fixture.session.requests.single().compaction != null)
        assertEquals(TrustLevel.AutoEdits, fixture.session.requests.single().trust)
        assertEquals(ApprovalMode.AutoEdits, fixture.storedApproval())
    }

    @Test
    fun `the handoff summary turn asks but its trust never becomes the chat's configuration`() = runTest {
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
    fun `a session retired while the change is saved keeps the chat's trust`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.access.otherSessions["chat"] = fixture.session
        fixture.access.beforeSave = {
            fixture.configuration.controller.retire("chat", fixture.session)
            fixture.native.configuration.value = fixture.native.configuration.value.copy(trust = TrustLevel.Ask)
        }

        fixture.configuration.controller.configure(fixture.access, "chat", StudioSettingChange.Effort("high"))
        runCurrent()

        assertEquals(ApprovalMode.AutoEdits, fixture.access.states.getValue("chat").applied?.approval)
        assertEquals(ApprovalMode.AutoEdits, fixture.access.saved.last().second.approval)
    }

    @Test
    fun `the carried seed reaches the turns until one marks it delivered even when marking fails`() = runTest {
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

    @Test
    fun `a review model reaches the native prompt`() = runTest {
        val fixture = SubmissionFixture(this)
        val parent = fixture.configuration.target.copy(model = ModelId("parent"))
        fixture.session.submitStudioPrompt("Hi", null, null, emptyList(), RequestId("review"), reviewTarget = parent)
        assertEquals(parent, fixture.session.requests.single().reviewTarget)
    }

    @Test
    fun `the plan of the chat is bound to its session before the native prompt`() = runTest {
        lateinit var fixture: SubmissionFixture
        val bound = mutableListOf<Triple<String, SessionRef, Int>>()
        val planning = object : StudioPlanning {
            override val availability: Flow<Boolean> = flowOf(true)
            override val plans: Flow<Map<String, Plan>> = flowOf(emptyMap())
            override suspend fun enter(chatId: String) = true
            override suspend fun leave(chatId: String) = true
            override suspend fun bind(chatId: String, session: SessionRef) {
                bound += Triple(chatId, session, fixture.session.requests.size)
            }

            override suspend fun startImplementation(chatId: String, revision: Long) = false
        }
        fixture = SubmissionFixture(this, planning = planning)

        fixture.submit("planning")

        assertEquals(listOf(Triple("chat", fixture.session.ref, 0)), bound)
        assertEquals(1, fixture.session.requests.size)
    }

    private fun PromptRequest.text(): String = parts.filterIsInstance<ContentPart.Text>().joinToString { it.text }
}

private class SubmissionFixture(
    test: TestScope,
    private val isTrustSupported: Boolean = true,
    planning: StudioPlanning = StudioPlanning.None,
) {
    val configuration = StudioConfigurationFixture(test)
    private val target: EngineTarget = configuration.target
    val access = configuration.access
    val native = ConfigurationNative(configuration.initial.copy(trust = TrustLevel.AutoEdits))
    val session = SubmissionSession(target, native, isTrustSupported)
    val security = MutableStateFlow(StudioSecurityLevel.Automatic)
    var securityReads = 0
    private val submission = StudioConfiguredSubmission(
        configuration.controller,
        StudioLearningPrompts(LearningOff, UnconfinedDispatchers),
        StudioHelperPolicy(ChecklistTestStores()),
        object : EffortChoicesView {
            override val state = MutableStateFlow<EffortConfigurationState>(EffortConfigurationState.Ready())
        },
        StudioRequestOrigins(lazyOf(emptySet())),
        object : StudioSecurityPolicy {
            override val levels = security
            override suspend fun level(): StudioSecurityLevel {
                securityReads += 1
                return security.value
            }
        },
        planning = planning,
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

    suspend fun submit(request: String, compaction: ContextCompaction? = null, isUserPrompt: Boolean = true): TurnId =
        submission.submit(
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
                isUserPrompt = isUserPrompt,
            ),
            { listOf(StudioModel(target.studioModelId(), "Model", isTrustSupported = isTrustSupported)) },
            { access.states },
        )

    fun storedApproval(): ApprovalMode? = access.records.getValue("chat").configuration?.approval
}

/** Publishes the trust of every accepted turn as its configuration, the way Pi does after acceptance. */
private class SubmissionSession(
    target: EngineTarget,
    private val native: ConfigurationNative,
    isTrustSupported: Boolean,
) : ActiveSession {
    private val trust = (object : AppliesTrustLevels {}).takeIf { isTrustSupported }
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
            listOfNotNull(prompts, native, trust).firstNotNullOfOrNull { key.type.safeCast(it) }
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
