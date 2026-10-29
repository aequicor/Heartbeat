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
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustedUserInstructions
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.stripHostDirectives
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioSecurityLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.DefaultRunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanning
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRunAdmission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSecurityPolicy
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSkillContent
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationState
import io.aequicor.heartbeat.feature.planningmode.api.Plan
import io.aequicor.heartbeat.feature.scheduler.api.ActionId
import io.aequicor.heartbeat.feature.scheduler.api.HelperModelOptions
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.reflect.safeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StudioConfiguredSubmissionTest {
    @Test
    fun `host admission waits for prompt preparation and a late refusal prevents native send`() = runTest {
        val fixture = SubmissionFixture(this)
        val prepared = CompletableDeferred<Unit>()
        fixture.beforeContext = { prepared.await() }
        var admissions = 0
        var allowed = true
        val gate = StudioHostSubmission(
            StudioRunAdmission {
                admissions++
                allowed
            },
            currentCoroutineContext(),
        )
        val pending = async {
            assertFailsWith<CancellationException> { fixture.submit("host", gate = gate) }
        }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(0, admissions)
        assertTrue(fixture.session.requests.isEmpty())
        allowed = false
        prepared.complete(Unit)
        pending.await()
        assertEquals(1, admissions)
        assertTrue(fixture.session.requests.isEmpty())
        assertTrue(gate.isCancelled)
    }

    @Test
    fun `browser mention follows the current input host and preserves the visible prompt`() = runTest {
        val fixture = SubmissionFixture(this)
        val environment = object : StudioChatEnvironment {
            override val preselected = mapOf("external_browser" to setOf("connection"))
            override fun bind(session: SessionRef): AutoCloseable = AutoCloseable {}
        }
        val prompt = "@browser открой страницу"
        fixture.submit("external", userInstruction = prompt, environment = environment)
        val external = fixture.session.requests.last().text()
        assertTrue(external.contains("Use ex_control_*"))
        assertEquals(prompt, stripHostDirectives(external))
        fixture.submit("embedded", userInstruction = prompt)
        val embedded = fixture.session.requests.last().text()
        assertTrue(embedded.contains("Use browser_*"))
        assertFalse(embedded.contains("ex_control_*"))
        assertEquals(prompt, stripHostDirectives(embedded))
        assertEquals(AgentToolDecisionMode.AutomaticSafety, fixture.session.requests.last().decisionMode)
        fixture.submit("plain", userInstruction = "Continue")
        assertFalse(fixture.session.requests.last().text().contains("invoked @browser"))
    }

    @Test
    fun `automatic prompts and compaction do not interpret browser mentions`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.submit("automatic", isUserPrompt = false, userInstruction = "@browser")
        assertFalse(fixture.session.requests.last().text().contains("invoked @browser"))
        fixture.submit("compact", compaction = ContextCompaction(), userInstruction = "@browser")
        assertFalse(fixture.session.requests.last().text().contains("invoked @browser"))
    }

    @Test
    fun `configured sender persists originals before send and automatic turns inherit them`() = runTest {
        val fixture = SubmissionFixture(this)
        val store = ChecklistTestStore(ChatSpec)
        val initial = fixture.access.records.getValue("chat").copy(humanAuthority = StudioHumanAuthority())
        store.set(ChatsKey, listOf(initial))
        fixture.authorityStore = store
        fixture.submit("human", userInstruction = "  Never read core  ")
        fixture.submit("automatic", isUserPrompt = false)
        val first = fixture.session.requests.first()
        val automatic = fixture.session.requests.last()
        assertNull(first.userInstruction)
        val authority = assertIs<TrustedUserInstructions.Complete>(first.trustedUserInstructions)
        assertEquals("  Never read core  ", authority.messages.single().text)
        assertEquals(authority, automatic.trustedUserInstructions)
        assertEquals("  Never read core  ", store.get(ChatsKey)?.single()?.humanAuthority?.messages?.single()?.text)
    }

    @Test
    fun `configured sender never crosses the native boundary when authority persistence fails`() = runTest {
        val fixture = SubmissionFixture(this)
        val store = ChecklistTestStore(ChatSpec)
        val initial = fixture.access.records.getValue("chat").copy(humanAuthority = StudioHumanAuthority())
        store.set(ChatsKey, listOf(initial))
        fixture.authorityStore = store
        store.failWrites = true
        assertFailsWith<IllegalStateException> {
            fixture.submit("human", userInstruction = "Read the selected file")
        }
        assertTrue(fixture.session.requests.isEmpty())
    }

    @Test
    fun `configured sender keeps missing legacy authority explicit even with new human text`() = runTest {
        val fixture = SubmissionFixture(this)
        val store = ChecklistTestStore(ChatSpec)
        store.set(ChatsKey, listOf(fixture.access.records.getValue("chat")))
        fixture.authorityStore = store
        fixture.submit("human", userInstruction = "Continue")
        val sent = fixture.session.requests.single()
        assertEquals(TrustedUserInstructions.Incomplete, sent.trustedUserInstructions)
        assertNull(sent.userInstruction)
    }

    @Test
    fun `replacement context survives size refusal and unknown acceptance without retrying`() = runTest {
        for (failure in listOf(
            EngineFailure.ContextLimitExceeded(100),
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown),
        )) {
            val fixture = SubmissionFixture(this)
            fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(
                pendingEngineContext = "boundary",
            )
            fixture.engineContext = "Historical data: the original task and tool result"
            fixture.session.failure = EngineException(failure)
            val attempt = async { assertFailsWith<EngineException> { fixture.submit("first") } }
            runCurrent()
            if (failure is EngineFailure.Request) {
                assertFalse(attempt.isCompleted)
                assertEquals(1, fixture.session.requests.size)
                fixture.session.close()
            }
            attempt.await()
            assertEquals(1, fixture.session.requests.size)
            assertEquals("boundary", fixture.access.records.getValue("chat").pendingEngineContext)
            assertEquals(0, fixture.access.consumedSeeds)
            assertTrue(fixture.session.requests.single().text().contains(fixture.engineContext.orEmpty()))
            assertTrue(fixture.preparedPrompt.orEmpty().contains("Hello"))
        }
    }

    @Test
    fun `linked project skills reach the engine as host directives without changing the typed text`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.skills = listOf(
            ComposerSkillContent("/open-pr", ".agents/skills/open-pr/SKILL.md", "---\nname: open-pr\n---\nOpen a PR"),
        )

        fixture.submit("skill")

        val text = fixture.session.requests.single().text()
        assertTrue("Open a PR" in text && ".agents/skills/open-pr/SKILL.md" in text)
        assertEquals("Hello", stripHostDirectives(text))
        assertEquals(listOf("Hello"), fixture.linkedTexts)
        assertEquals(fixture.session.route.engine, fixture.linkedScopes.single().target?.engine)
    }

    @Test
    fun `skills are not linked for agent prompts or slash commands`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.skills = listOf(ComposerSkillContent("/open-pr", ".agents/skills/open-pr/SKILL.md", "Open a PR"))

        fixture.submit("agent", isUserPrompt = false)
        fixture.submit("command", userInstruction = "/compact [/open-pr](.agents/skills/open-pr/SKILL.md)")

        assertEquals(emptyList(), fixture.linkedTexts)
        assertTrue(fixture.session.requests.none { "Open a PR" in it.text() })
    }

    @Test
    fun `accepted first replacement turn consumes its boundary exactly once`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(pendingEngineContext = "boundary")
        fixture.engineContext = "Historical data: original task"
        fixture.submit("first")
        assertEquals(1, fixture.access.consumedSeeds)
        assertEquals(null, fixture.access.records.getValue("chat").pendingEngineContext)
        fixture.submit("next")
        assertEquals(1, fixture.access.consumedSeeds)
        assertFalse(fixture.session.requests.last().text().contains("Historical data"))
    }

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
            listOf(TrustLevel.Ask, TrustLevel.Full, TrustLevel.AutoEdits),
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
    fun `full profile trust applies to host jobs compaction and handoff`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.security.value = StudioSecurityLevel.Trust

        fixture.submit("scheduled", isUserPrompt = false)
        fixture.submit("compact", compaction = ContextCompaction())
        fixture.submit("${HANDOFF_REQUEST_PREFIX}1")

        assertEquals(
            listOf(TrustLevel.Full, TrustLevel.Full, TrustLevel.Full),
            fixture.session.requests.map { it.trust },
        )
        assertTrue(fixture.session.requests.all { it.decisionMode == AgentToolDecisionMode.Interactive })
        assertEquals(fixture.session.requests.size, fixture.securityReads)
    }

    @Test
    fun `full profile trust applies to a helper regardless of its legacy ceiling`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.security.value = StudioSecurityLevel.Trust
        fixture.access.records["chat"] = fixture.access.records.getValue("chat").copy(
            helper = StudioHelperIdentity(ActionId("owner"), null, null, TrustLevel.Ask),
        )

        fixture.submit("helper-user-message")

        val request = fixture.session.requests.single()
        assertEquals(TrustLevel.Full, request.trust)
        assertEquals(AgentToolDecisionMode.Interactive, request.decisionMode)
        assertEquals(fixture.session.requests.size, fixture.securityReads)
    }

    @Test
    fun `helper turns retain their model metadata and use full profile trust`() = runTest {
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
            assertEquals(AgentToolDecisionMode.Interactive, request.decisionMode)
            assertEquals(TrustLevel.Full, request.trust)
        }
        assertEquals(fixture.session.requests.size, fixture.securityReads)
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
    fun `handoff follows the profile mode without changing the chat configuration`() = runTest {
        val fixture = SubmissionFixture(this)
        fixture.submit("prompt")
        runCurrent()

        fixture.submit("${HANDOFF_REQUEST_PREFIX}1")
        runCurrent()

        assertEquals(TrustLevel.AutoEdits, fixture.session.requests.last().trust)
        assertEquals(TrustLevel.AutoEdits, fixture.native.configuration.value.trust)
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
        val planning = BindingPlanning { chatId, session ->
            bound += Triple(chatId, session, fixture.session.requests.size)
        }
        fixture = SubmissionFixture(this, planning = planning)

        fixture.submit("planning")

        assertEquals(listOf(Triple("chat", fixture.session.ref, 0)), bound)
        assertEquals(1, fixture.session.requests.size)
    }

    @Test
    fun `a plan that cannot follow the chat into its session keeps the prompt unsent`() = runTest {
        val planning = BindingPlanning { _, _ -> error("The plan could not follow the chat into its session") }
        val fixture = SubmissionFixture(this, planning = planning)

        assertFailsWith<IllegalStateException> { fixture.submit("planning") }

        assertEquals(0, fixture.session.requests.size)
    }

    private class BindingPlanning(private val onBind: (String, SessionRef) -> Unit) : StudioPlanning {
        override val availability: Flow<Boolean> = flowOf(true)
        override val plans: Flow<Map<String, Plan>> = flowOf(emptyMap())
        override suspend fun canEnter() = true
        override suspend fun enter(chatId: String) = true
        override suspend fun leave(chatId: String) = true
        override suspend fun bind(chatId: String, session: SessionRef) = onBind(chatId, session)
        override suspend fun startImplementation(chatId: String, revision: Long) = false
        override suspend fun approve(chatId: String, revision: Long) = false
        override suspend fun write(chatId: String, text: String, base: Long) = false
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
    var engineContext: String? = null
    var preparedPrompt: String? = null
    var beforeContext: suspend () -> Unit = {}
    var authorityStore: ChecklistTestStore? = null
    var skills: List<ComposerSkillContent> = emptyList()
    val linkedTexts = mutableListOf<String>()
    val linkedScopes = mutableListOf<ComposerScope>()
    private val submissionAccess = object : StudioConfigurationAccess by access {
        override suspend fun captureUserInstructions(
            id: String,
            request: RequestId,
            instruction: String?,
        ): TrustedUserInstructions = authorityStore?.let {
            captureStudioUserInstructions(it, id, request, instruction)
        } ?: TrustedUserInstructions.Incomplete

        override suspend fun engineSwitchContext(id: String, incoming: String): String? {
            beforeContext()
            preparedPrompt = incoming
            return engineContext.takeIf { access.records.getValue(id).pendingEngineContext != null }
        }
    }
    val native = ConfigurationNative(configuration.initial.copy(trust = TrustLevel.AutoEdits))
    val session = SubmissionSession(target, native, isTrustSupported)
    val security = MutableStateFlow(StudioSecurityLevel.Automatic)
    var securityReads = 0
    private val submission = StudioConfiguredSubmission(
        configuration.controller,
        StudioLearningPrompts(LearningOff, UnconfinedDispatchers),
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
        object : ComposerAssists {
            override suspend fun referencedSkills(text: String, scope: ComposerScope): List<ComposerSkillContent> {
                linkedTexts += text
                linkedScopes += scope
                return skills
            }

            override suspend fun suggest(
                trigger: ComposerTrigger,
                scope: ComposerScope,
                hostCommands: List<HostCommand>,
            ): List<ComposerSuggestion> = emptyList()
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

    suspend fun submit(
        request: String,
        compaction: ContextCompaction? = null,
        isUserPrompt: Boolean = true,
        userInstruction: String? = null,
        environment: StudioChatEnvironment? = null,
        gate: StudioSubmissionGate? = null,
    ): TurnId = submission.submit(
        submissionAccess,
        session,
        target,
        StudioTurnRequest(
            "chat",
            prompt = if (compaction == null) userInstruction ?: "Hello" else "",
            settings = DefaultRunSettings,
            kind = WorktreeRunKind.Coding,
            request = RequestId(request),
            compaction = compaction,
            isUserPrompt = isUserPrompt,
            userInstruction = userInstruction,
            environment = environment,
            submission = gate,
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
    var failure: EngineException? = null
    override val ref = SessionRef(target.engine, SessionSourceId("source"), "native")
    override val route = ExecutionRoute(target.engine, target.binding, AuthSourceId("auth"), AuthRevision.Known("1"))
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    private val prompts = object : SendsPrompts {
        override suspend fun send(request: PromptRequest): TurnId {
            requests += request
            failure?.let { throw it }
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
