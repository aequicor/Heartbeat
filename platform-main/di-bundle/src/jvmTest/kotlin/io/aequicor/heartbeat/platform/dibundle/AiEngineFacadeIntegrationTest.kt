package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreateSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCheckpoint
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HistoryPageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ItemInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionEvent
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.Turn
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.declaresCompatibleProviders
import io.aequicor.heartbeat.feature.aiengine.facade.api.hasCompatibleProviders
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.AttachesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRuntime
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.reflect.safeCast
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Profile entry points of the AI-engine services, for this test only. */
@ContributesTo(ProfileScope::class)
interface AiEngineTestAccessors {
    val engineFacade: EngineFacade
    val engineAuthSources: AuthSources
    val studioRepository: StudioRepository
    val studioRuntime: StudioRuntime
    val modelSelections: ModelSelections
    val engineRegistrations: Set<EngineRegistration>
}

/** A scripted adapter bundled only into the test graph, registered like a real adapter. */
@ContributesTo(ProfileScope::class)
interface FacadeTestEngineContribution {
    @Provides
    @IntoSet
    fun testEngine(): EngineRegistration = TestAdapter.registration
}

/** The adapter's own toggle. */
@ContributesTo(AppScope::class)
interface TestEngineToggleContribution {
    @Provides
    @IntoSet
    fun testEngineToggle(): FeatureToggle<*> = TestAdapter.toggle
}

object TestAdapter {
    val engine = EngineId("itest")
    val toggle = FeatureToggle.Flag("itest.engine", "Integration test engine")
    val runtimes = mutableListOf<TestRuntime>()

    val registration = EngineRegistration(
        EngineDescriptor(
            engine,
            "Integration engine",
            EngineFamily.Vendor,
            EnginePlatform.entries.toSet(),
            toggle,
            declaredFeatures = setOf(CreatesSessions.id, AttachesSessions.id),
            connectionMethods = listOf(
                ConnectionMethod.ApiKey(
                    ConnectionMethodId("key"),
                    ProviderInfo(ProviderId("openai"), "Test provider"),
                    EndpointOrigin("https://api.example.com"),
                ),
            ),
        ),
        AuthOwnerId("itest-cli"),
        lazyOf(
            object : EngineFactory {
                override suspend fun checkRequirements() = EngineAvailability.Available

                override fun accepts(source: AuthSource, context: EngineContext) = source is AuthSource.ManagedKey

                override fun authContext(context: EngineContext) = AuthContextKey("itest.default")

                override suspend fun bind(binding: EngineBindingId, source: AuthSource) = Unit

                override suspend fun unbind(binding: EngineBindingId) = Unit

                override suspend fun discoverModels(source: AuthSource, context: EngineContext) =
                    listOf(ModelInfo(EngineTarget(engine, context.binding, ModelId("m1")), "Model"))

                override suspend fun createRuntime(identity: RuntimeIdentity): EngineRuntime =
                    TestRuntime(identity).also { runtimes += it }
            },
        ),
    )
}

class TestRuntime(override val identity: RuntimeIdentity) : EngineRuntime {
    val natives = mutableListOf<TestNative>()

    /** Close calls; the profile releases runtimes asynchronously on the app scope. */
    val closes = MutableStateFlow(0)

    override val features: EngineFeatures = features(
        CreatesSessions to object : CreatesSessions {
            override suspend fun create(request: CreateSessionRequest): ActiveSession =
                TestNative(SessionRef(identity.engine, SessionSourceId("local"), "n${natives.size}")).also {
                    natives += it
                }
        },
        AttachesSessions to object : AttachesSessions {
            override suspend fun attach(ref: SessionRef, request: ResumeSessionRequest): ActiveSession =
                natives.single {
                    it.ref == ref
                }.also { it.native.value = ActiveSessionState.Ready() }
        },
    )

    override suspend fun close() {
        closes.value++
    }
}

class TestNative(override val ref: SessionRef) : ActiveSession {
    val native = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    private val events = MutableStateFlow<List<SessionEvent>>(emptyList())
    private var items = emptyList<SessionItem>()
    val closeStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
    var closeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var cancellations = 0
    var cancelGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var failCancellation = false
    var decision: PermissionDecision? = null
    override val route get() = error("the facade owns the route")
    override val state: StateFlow<ActiveSessionState> = native

    override val features: EngineFeatures = features(
        SendsPrompts to object : SendsPrompts {
            override suspend fun send(request: PromptRequest): TurnId {
                val turn = Turn(
                    TurnId("native-${items.size}"),
                    request.id,
                    EngineTarget(ref.engine, bindingOf(), ModelId("m1")),
                )
                record(
                    SessionItem.Message(
                        ItemInfo(ItemId("prompt-${items.size}"), items.size.toLong(), 0, turn.id),
                        MessageRole.User,
                        request.parts,
                    ),
                )
                native.value = ActiveSessionState.Running(turn)
                return turn.id
            }
        },
        CancelsTurns to object : CancelsTurns {
            override suspend fun cancel(turn: TurnId) {
                cancellations++
                cancelGate?.await()
                if (failCancellation) {
                    throw EngineException(
                        EngineFailure.Transport(
                            io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason.NetworkUnavailable,
                        ),
                    )
                }
                val current = when (val status = native.value) {
                    is ActiveSessionState.Running -> status.turn
                    is ActiveSessionState.AwaitingUserAction -> status.turn
                    else -> error("not running")
                }
                check(current.id == turn)
                native.value = ActiveSessionState.Ready(current.copy(outcome = TurnOutcome.Cancelled))
            }
        },
        RequestsPermissions to object : RequestsPermissions {
            override suspend fun respond(decision: PermissionDecision) {
                this@TestNative.decision = decision
                val pending = native.value as ActiveSessionState.AwaitingUserAction
                native.value = ActiveSessionState.Running(pending.turn)
            }
        },
        SessionHistory to object : SessionHistory {
            override suspend fun page(request: HistoryPageRequest) = HistoryPage(
                items,
                null,
                null,
                HistoryCheckpoint(events.value.size.toString()),
                HistoryCoverage.Complete,
            )

            override fun watch(after: HistoryCheckpoint): Flow<SessionEvent> = flow {
                var offset = after.value.toInt()
                events.collect { journal ->
                    journal.drop(offset).forEach { emit(it) }
                    offset = journal.size
                }
            }
        },
    )

    private fun record(item: SessionItem) {
        items = items + item
        events.value += SessionEvent.ItemUpserted(HistoryCheckpoint((events.value.size + 1).toString()), item)
    }

    fun requestPermission() {
        val turn = (native.value as ActiveSessionState.Running).turn
        native.value = ActiveSessionState.AwaitingUserAction(
            turn,
            listOf(
                PermissionRequest(
                    PermissionRequestId("approval"),
                    turn.id,
                    "Write file",
                    listOf(PermissionOption(PermissionOptionId("allow-once"), "Allow once")),
                ),
            ),
        )
    }

    fun finish() {
        val turn = checkNotNull((native.value as ActiveSessionState.Running).turn)
        record(
            SessionItem.Message(
                ItemInfo(ItemId("answer-${items.size}"), items.size.toLong(), 0, turn.id),
                MessageRole.Assistant,
                listOf(ContentPart.Text("Native answer")),
            ),
        )
        native.value = ActiveSessionState.Ready(turn.copy(outcome = TurnOutcome.Completed))
    }

    override suspend fun close() {
        closeStarted.complete(Unit)
        closeGate?.await()
        native.value = ActiveSessionState.Closed
    }

    private fun bindingOf() = EngineBindingId("native")
}

private fun features(vararg entries: Pair<EngineFeatureKey<*>, EngineFeature>) = object : EngineFeatures {
    override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
        entries.firstOrNull { it.first.id == key.id }?.second?.let { key.type.safeCast(it) }
            ?.let { FeatureAccess.Available(it) }
            ?: FeatureAccess.Unsupported
}

class AiEngineFacadeIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val scope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))

    @BeforeTest
    fun setUp() {
        val os = System.getProperty("os.name")
        assumeTrue(os.startsWith("Windows") || os.startsWith("Mac"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TestAdapter.runtimes.clear()
    }

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    @Test
    fun everyNonVendorEngineAcceptsCompatibleProviders() = runTest {
        val accessors = app.profileSessions.open(ProfileId("compatible")).graph as AiEngineTestAccessors
        val nonVendor = accessors.engineRegistrations.map { it.descriptor }
            .filter { it.family.hasCompatibleProviders }
        assertTrue(nonVendor.isNotEmpty())
        nonVendor.forEach { descriptor ->
            assertTrue(declaresCompatibleProviders(descriptor), "${descriptor.id.value} lacks compatible providers")
        }
    }

    @Test
    fun createdSessionRunsATurnThroughTheMachineAndIsIndexed() = runTest {
        val toggles = app as TestToggleAccessors
        toggles.toggleControl.setOverride(AiEngines, true)
        toggles.toggleControl.setOverride(TestAdapter.toggle, true)
        val accessors = app.profileSessions.open(ProfileId("ai-engine")).graph as AiEngineTestAccessors
        val facade = accessors.engineFacade

        val source = Secret(
            "sk-test".toCharArray(),
        ).use { accessors.engineAuthSources.addManagedKey("work", scope, it) }
        val binding = facade.bindings.connect(TestAdapter.engine, source.info.id)
        // Bundled adapters (Pi, …) may be listed too; only the test engine is asserted.
        val info = facade.engines.state.first { list -> list.any { it.descriptor.id == TestAdapter.engine } }
            .single { it.descriptor.id == TestAdapter.engine }
        assertEquals(listOf(binding), info.bindings)
        assertEquals(1, facade.models.refresh(TestAdapter.engine, binding.id).models.size)

        val creator = facade.engines.features(TestAdapter.engine).resolve(CreatesSessions)
        val session = assertIs<FeatureAccess.Available<CreatesSessions>>(creator).feature
            .create(CreateSessionRequest(EngineTarget(TestAdapter.engine, binding.id, ModelId("m1"))))
        val sender = assertIs<FeatureAccess.Available<SendsPrompts>>(session.features.resolve(SendsPrompts)).feature

        val turn = sender.send(PromptRequest(RequestId("r1"), listOf(ContentPart.Text("hello"))))
        assertEquals(
            turn,
            (session.state.first { it is ActiveSessionState.Running } as ActiveSessionState.Running).turn.id,
        )
        val busy = assertFailsWith<EngineException> { facade.bindings.disconnect(binding.id) }
        assertEquals(EngineFailure.Session(SessionFailureReason.Busy), busy.failure)

        TestAdapter.runtimes.single().natives.single().finish()
        val ready = session.state.first { it is ActiveSessionState.Ready && it.lastTurn != null }
        assertEquals(TurnOutcome.Completed, (ready as ActiveSessionState.Ready).lastTurn?.outcome)
        assertEquals(SessionOrigin.Heartbeat, facade.sessions.page().items.single { it.ref == session.ref }.origin)

        session.close()
        assertEquals(ActiveSessionState.Closed, session.state.value)
        facade.bindings.disconnect(binding.id)
        app.profileSessions.close()
        // closeAll runs asynchronously on the app scope: await it instead of sampling.
        TestAdapter.runtimes.single().closes.first { it > 0 }
        assertEquals(1, TestAdapter.runtimes.single().closes.value)
    }
}
