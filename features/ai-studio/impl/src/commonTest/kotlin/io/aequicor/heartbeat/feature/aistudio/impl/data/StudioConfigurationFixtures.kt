package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
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
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ExecutionRoute
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationUpdate
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import io.aequicor.heartbeat.feature.feedback.api.FeedbackEnabled
import io.aequicor.heartbeat.feature.feedback.api.FeedbackIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackMachineKey
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutput
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import io.aequicor.heartbeat.feature.feedback.api.FeedbackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

internal class StudioConfigurationFixture(test: TestScope) {
    val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("initial"))
    val initial = SessionConfiguration(target.model, "low", TrustLevel.Ask)
    val registry = ConfigurationRegistry()
    val access = ConfigurationAccess(target, initial)
    private val enabled = MutableStateFlow(true)
    var isFeedbackEnabled: Boolean
        get() = enabled.value
        set(value) {
            enabled.value = value
        }
    private val toggles = object : FeatureToggles {
        @Suppress("UNCHECKED_CAST") // Feedback is the only Boolean toggle used by this fixture.
        override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T =
            if (toggle == FeedbackEnabled) isFeedbackEnabled as T else toggle.default

        @Suppress("UNCHECKED_CAST") // Feedback is the only Boolean toggle used by this fixture.
        override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> =
            if (toggle == FeedbackEnabled) enabled as Flow<T> else flowOf(toggle.default)
    }
    private val profile = object : ScopeHandle {
        override val name = "configuration-test-profile"
        override val coroutineScope: CoroutineScope = test.backgroundScope
        override val isClosed = false
        override val savedState: ScopeSavedState get() = error("Unused")
        override fun onClose(action: () -> Unit): DisposableHandle = DisposableHandle {}
    }
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(100)
    }
    val controller = StudioConfigurationController(profile, registry, toggles, clock)

    fun add(id: String = "chat"): ConfigurationNative {
        val native = ConfigurationNative(initial)
        access.add(id, ConfigurationSession(target, id, native))
        return native
    }
}

internal class ConfigurationNative(initial: SessionConfiguration) : ChangesSessionConfiguration {
    override val configuration = MutableStateFlow(initial)
    override val updates = MutableSharedFlow<SessionConfigurationUpdate>(extraBufferCapacity = 10)
    val calls = mutableListOf<Pair<String, SessionConfigurationChange>>()
    var onApply: suspend (String, SessionConfigurationChange) -> SessionConfiguration = { _, change ->
        when (change) {
            is SessionConfigurationChange.Model -> configuration.value.copy(model = change.model)
            is SessionConfigurationChange.Effort -> configuration.value.copy(reasoningEffort = change.effort)
            is SessionConfigurationChange.Trust -> configuration.value.copy(trust = change.trust)
        }
    }

    override suspend fun apply(operationId: String, change: SessionConfigurationChange): SessionConfiguration {
        calls += operationId to change
        return onApply(operationId, change).also { configuration.value = it }
    }
}

internal class ConfigurationSession(target: EngineTarget, id: String, var native: ChangesSessionConfiguration?) :
    ActiveSession {
    override val ref = SessionRef(target.engine, SessionSourceId("source"), id)
    override val route = ExecutionRoute(target.engine, target.binding, AuthSourceId("auth"), AuthRevision.Known("1"))
    override val state = MutableStateFlow<ActiveSessionState>(ActiveSessionState.Ready())
    override val features = object : EngineFeatures {
        @Suppress("UNCHECKED_CAST") // This fixture resolves only the exact live configuration key.
        override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> =
            if (key == ChangesSessionConfiguration && native != null) {
                FeatureAccess.Available(requireNotNull(native) as F)
            } else {
                FeatureAccess.Unsupported
            }
    }

    override suspend fun close() {
        state.value = ActiveSessionState.Closed
    }
}

internal class ConfigurationAccess(private val target: EngineTarget, private val initial: SessionConfiguration) :
    StudioConfigurationAccess {
    val records = mutableMapOf<String, StudioChatRecord>()
    val sessions = mutableMapOf<String, ConfigurationSession>()
    val states = mutableMapOf<String, StudioSessionConfiguration>()
    val saved = mutableListOf<Pair<String, StudioSessionSettings>>()
    val validated = mutableListOf<EngineTarget>()
    val attached = mutableListOf<String>()
    val anchors = mutableMapOf<String, FeedbackAnchor>()
    var beforeSave: suspend () -> Unit = {}

    fun add(id: String, session: ConfigurationSession) {
        sessions[id] = session
        records[id] = StudioChatRecord(
            id,
            id,
            Instant.fromEpochSeconds(0),
            ref = session.ref,
            target = target,
            configuration = initial.studio(target),
        )
    }

    override suspend fun configurationRecord(id: String): StudioChatRecord = records.getValue(id)
    override suspend fun configurationAnchor(id: String): FeedbackAnchor =
        anchors[id] ?: FeedbackAnchor(records.getValue(id).ref)
    override suspend fun configurationSession(id: String, target: EngineTarget): ActiveSession {
        attached += id
        return sessions.getValue(id)
    }

    override suspend fun saveConfiguration(id: String, settings: StudioSessionSettings) {
        beforeSave()
        saved += id to settings
        records[id] = records.getValue(id).copy(configuration = settings, target = studioModelTarget(settings.modelId))
    }

    override suspend fun validateConfigurationTarget(target: EngineTarget) {
        validated += target
    }

    override fun configurationModels(): List<StudioModel> =
        listOf(StudioModel(target.studioModelId(), "Initial", reasoningEfforts = listOf("low", "high")))

    override fun configurationState(id: String, state: StudioSessionConfiguration) {
        states[id] = state
    }
}

internal class ConfigurationRegistry : MachineRegistry {
    val intents = mutableListOf<MachineIntent>()
    val publications: List<FeedbackRecord> get() = intents.filterIsInstance<FeedbackIntent.Public.Publish>().map {
        it.record
    }
    private val feedback = object : MachineRef<FeedbackState, FeedbackIntent.Public, FeedbackOutput> {
        override val name = FeedbackMachineKey.name
        override val state = MutableStateFlow<FeedbackState>(FeedbackState.Ready())
        override val outputs: Flow<FeedbackOutput> = emptyFlow()

        override suspend fun send(intent: FeedbackIntent.Public): SendResult {
            val record = (intent as FeedbackIntent.Public.Publish).record
            val records = (state.value as FeedbackState.Ready).records
            val index = records.indexOfFirst { it.id == record.id }
            val updated = if (index < 0) {
                records + record
            } else {
                records.mapIndexed { current, previous -> if (current == index) record else previous }
            }
            state.value = FeedbackState.Ready(updated)
            return SendResult.Accepted
        }
    }

    @Suppress("UNCHECKED_CAST") // The fake resolves only the exact feedback key.
    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> find(
        key: MachineKey<S, I, P, E, O>,
    ): MachineRef<S, P, O>? = if (key == FeedbackMachineKey) feedback as MachineRef<S, P, O> else null

    override fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> observe(
        key: MachineKey<S, I, P, E, O>,
    ): StateFlow<MachineRef<S, P, O>?> = MutableStateFlow(find(key))

    override suspend fun <S : MachineState, I : MachineIntent, P : I, E : MachineEffect, O : MachineOutput> send(
        key: MachineKey<S, I, P, E, O>,
        intent: P,
    ): SendResult {
        intents += intent
        if (key == FeedbackMachineKey) feedback.send(intent as FeedbackIntent.Public)
        return SendResult.Accepted
    }
}
