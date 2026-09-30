package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionConfigurationChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionSettings
import io.aequicor.heartbeat.feature.aistudio.api.StudioSettingChange
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelId
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationIntent
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortConfigurationMachineKey
import io.aequicor.heartbeat.feature.feedback.api.FeedbackAnchor
import io.aequicor.heartbeat.feature.feedback.api.FeedbackChange
import io.aequicor.heartbeat.feature.feedback.api.FeedbackEnabled
import io.aequicor.heartbeat.feature.feedback.api.FeedbackIntent
import io.aequicor.heartbeat.feature.feedback.api.FeedbackMachineKey
import io.aequicor.heartbeat.feature.feedback.api.FeedbackOutcome
import io.aequicor.heartbeat.feature.feedback.api.FeedbackRecord
import io.aequicor.heartbeat.feature.feedback.api.FeedbackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Conversation persistence stays under the repository's existing record lock. */
internal interface StudioConfigurationAccess {
    suspend fun configurationRecord(id: String): StudioChatRecord
    suspend fun configurationAnchor(id: String): FeedbackAnchor
    suspend fun configurationSession(id: String, target: EngineTarget): ActiveSession
    suspend fun saveConfiguration(id: String, settings: StudioSessionSettings)
    suspend fun validateConfigurationTarget(target: EngineTarget)
    fun configurationModels(): List<StudioModel>
    fun configurationState(id: String, state: StudioSessionConfiguration)
}

/** Applies profile-owned changes independently of the screen and publishes correlated feedback. */
@SingleIn(ProfileScope::class)
@Inject
internal class StudioConfigurationController(
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val clock: Clock,
) {
    private val log = Log.tag("StudioConfiguration")
    private val lock = Mutex()
    private val changing = mutableSetOf<String>()
    private val observed = mutableMapOf<String, ActiveSession>()
    private val observedTargets = mutableMapOf<String, EngineTarget>()
    private val reports = mutableMapOf<String, FeedbackRecord>()
    private val published = mutableSetOf<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun feedback(id: String): Flow<List<FeedbackRecord>> = combine(
        machines.observe(FeedbackMachineKey).flatMapLatest { machine ->
            machine?.state?.map { (it as? FeedbackState.Ready)?.records.orEmpty() } ?: flowOf(emptyList())
        },
        toggles.observe(FeedbackEnabled),
    ) { records, enabled -> if (enabled) records.filter { it.source == id } else emptyList() }

    suspend fun configure(access: StudioConfigurationAccess, id: String, change: StudioSettingChange) {
        // The profile owns the accepted operation; awaiting it only attaches this screen effect.
        profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) {
            if (!lock.withLock { changing.add(id) }) return@async
            var completion: StudioSessionSettings? = null
            try {
                completion = apply(access, id, change)
            } finally {
                withContext(NonCancellable) { complete(access, id, completion) }
            }
        }.await()
    }

    /** Releases the operation and its menu guard together, using the latest acknowledged native snapshot. */
    private suspend fun complete(access: StudioConfigurationAccess, id: String, completion: StudioSessionSettings?) {
        val latest = lock.withLock {
            changing.remove(id)
            val feature = observed[id]?.features?.resolve(ChangesSessionConfiguration)
            val native = (feature as? FeatureAccess.Available)?.feature?.configuration?.value
            val target = observedTargets[id]
            val settings = if (native != null && target != null) native.studio(target) else completion
            if (settings != null) access.configurationState(id, StudioSessionConfiguration(settings))
            settings.takeIf { native != null }
        }
        if (latest != null) persist(access, id, latest)
    }

    private suspend fun apply(
        access: StudioConfigurationAccess,
        id: String,
        change: StudioSettingChange,
    ): StudioSessionSettings? {
        val record = access.configurationRecord(id)
        val target = record.configurationTarget()
        var actual = record.configuration?.native(target)
            ?: SessionConfiguration(target.model)
        val requested = change.feedback(target, actual)
        if (change.matches(target, actual)) return null
        val operation = Uuid.random().toString()
        val report = FeedbackRecord(
            id = operation,
            source = id,
            revision = 0,
            createdAt = clock.now(),
            anchor = access.configurationAnchor(id),
            change = requested,
            outcome = FeedbackOutcome.Pending,
        )
        access.configurationState(id, StudioSessionConfiguration(actual.studio(target), operation))
        publish(report)
        return try {
            val selected = requested.selectedTarget(target)
            access.validateConfigurationTarget(selected)
            val session = access.configurationSession(id, target)
            val capability = session.configurationCapability()
            val nativeChange = change.native(selected)
            actual = if (capability != null) {
                observe(access, id, session, target)
                capability.apply(operation, nativeChange)
            } else {
                applyIdle(access, session, selected, actual, nativeChange)
            }
            val confirmedTarget = target.copy(model = actual.model)
            val settings = actual.studio(confirmedTarget)
            // Reflect the native acknowledgement even if persisting the preference subsequently fails.
            access.configurationState(id, StudioSessionConfiguration(settings, operation))
            publish(report.copy(revision = 1, outcome = FeedbackOutcome.Applied(actual)))
            persist(access, id, settings)
            val latest = capability?.configuration?.value ?: actual
            val latestTarget = target.copy(model = latest.model)
            if (change is StudioSettingChange.Effort) saveEffort(latestTarget, latest.reasoningEffort)
            observe(access, id, session, confirmedTarget)
            latest.studio(latestTarget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Session parameter change failed" }
            val failure = (e as? EngineException)?.failure ?: EngineFailure.Unknown()
            publish(report.copy(revision = 1, outcome = FeedbackOutcome.Failed(failure)))
            actual.studio(target.copy(model = actual.model))
        }
    }

    private suspend fun applyIdle(
        access: StudioConfigurationAccess,
        session: ActiveSession,
        target: EngineTarget,
        current: SessionConfiguration,
        change: SessionConfigurationChange,
    ): SessionConfiguration {
        if (session.state.value !is ActiveSessionState.Ready) unsupported()
        return when (change) {
            is SessionConfigurationChange.Model -> {
                val switcher = when (val available = session.features.resolve(SwitchesModels)) {
                    is FeatureAccess.Available -> available.feature
                    is FeatureAccess.Unavailable -> throw EngineException(available.reason)
                    FeatureAccess.Unsupported -> unsupported()
                }
                switcher.switchTo(change.model)
                current.copy(model = change.model, reasoningEffort = null)
            }

            is SessionConfigurationChange.Effort -> {
                val levels = access.configurationModels().reasoningEfforts(target)
                if (change.effort != null && change.effort !in levels) {
                    throw EngineException(EngineFailure.Request(RequestFailureReason.Invalid))
                }
                current.copy(reasoningEffort = change.effort)
            }

            is SessionConfigurationChange.Trust -> unsupported()
        }
    }

    /** Attaches once per native handle; observation and provider corrections outlive screen effects. */
    suspend fun observe(access: StudioConfigurationAccess, id: String, session: ActiveSession, target: EngineTarget) {
        val capability = (session.features.resolve(ChangesSessionConfiguration) as? FeatureAccess.Available)?.feature
            ?: return
        val isAttached = lock.withLock {
            if (observed[id] === session) {
                false
            } else {
                observed[id] = session
                observedTargets[id] = target
                true
            }
        }
        if (!isAttached) return
        profile.coroutineScope.launch {
            mirrorConfiguration(access, id, session, target, capability)
        }
        profile.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            mirrorCorrections(access, id, session, target, capability)
        }
    }

    private suspend fun canReflect(id: String, session: ActiveSession): Boolean =
        lock.withLock { id !in changing && observed[id] === session }

    private suspend fun mirrorConfiguration(
        access: StudioConfigurationAccess,
        id: String,
        session: ActiveSession,
        target: EngineTarget,
        capability: ChangesSessionConfiguration,
    ) {
        try {
            capability.configuration.collect { native ->
                if (!canReflect(id, session)) return@collect
                val settings = native.studio(target.copy(model = native.model))
                access.configurationState(id, StudioSessionConfiguration(settings))
                persist(access, id, settings)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Could not observe session configuration" }
        }
    }

    private suspend fun mirrorCorrections(
        access: StudioConfigurationAccess,
        id: String,
        session: ActiveSession,
        target: EngineTarget,
        capability: ChangesSessionConfiguration,
    ) {
        capability.updates.collect { update ->
            val previous = lock.withLock { reports[update.operationId] } ?: return@collect
            publish(previous.copy(revision = previous.revision + 1, outcome = FeedbackOutcome.Failed(update.failure)))
            if (!canReflect(id, session)) return@collect
            val native = capability.configuration.value
            val latestTarget = target.copy(model = native.model)
            val latest = native.studio(latestTarget)
            access.configurationState(id, StudioSessionConfiguration(latest))
            persist(access, id, latest)
            if (previous.change is FeedbackChange.Effort) saveEffort(latestTarget, latest.reasoningEffort)
        }
    }

    private suspend fun persist(access: StudioConfigurationAccess, id: String, settings: StudioSessionSettings) {
        try {
            access.saveConfiguration(id, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Confirmed session configuration could not be saved; keeping it in this profile" }
        }
    }

    private suspend fun publish(record: FeedbackRecord) {
        if (profile.isClosed) return
        val isAccepted = lock.withLock {
            val previous = reports[record.id]
            if (previous != null && previous.revision >= record.revision) {
                false
            } else {
                reports[record.id] = record
                true
            }
        }
        if (!isAccepted) return
        val isEnabled = toggles.get(FeedbackEnabled)
        val isPublicationNeeded = lock.withLock {
            record.id in published || (isEnabled && published.add(record.id))
        }
        if (!isPublicationNeeded || profile.isClosed) return
        val result = machines.send(FeedbackMachineKey, FeedbackIntent.Public.Publish(record))
        if (result != SendResult.Accepted) log.w { "Feedback publication was not accepted: $result" }
    }

    private suspend fun saveEffort(target: EngineTarget, effort: String?) {
        if (profile.isClosed) return
        val result = machines.send(
            EffortConfigurationMachineKey,
            EffortConfigurationIntent.Public.Select(target, effort),
        )
        if (result != SendResult.Accepted) log.w { "Effort preference was not accepted: $result" }
    }
}

private fun unsupported(): Nothing =
    throw EngineException(EngineFailure.Engine(EngineFailureReason.UnsupportedCapability))

private fun StudioChatRecord.configurationTarget(): EngineTarget =
    target ?: configuration?.modelId?.let(::studioModelTarget)
        ?: throw EngineException(EngineFailure.Session(SessionFailureReason.NotFound))

private fun FeedbackChange.selectedTarget(current: EngineTarget): EngineTarget {
    val selected = (this as? FeedbackChange.Model)?.requested ?: current
    if (selected.engine != current.engine || selected.binding != current.binding) {
        throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))
    }
    return selected
}

private fun ActiveSession.configurationCapability(): ChangesSessionConfiguration? =
    when (val available = features.resolve(ChangesSessionConfiguration)) {
        is FeatureAccess.Available -> available.feature
        is FeatureAccess.Unavailable -> throw EngineException(available.reason)
        FeatureAccess.Unsupported -> null
    }

internal fun SessionConfiguration.studio(target: EngineTarget): StudioSessionSettings = StudioSessionSettings(
    target.copy(model = model).studioModelId(),
    reasoningEffort,
    when (trust) {
        TrustLevel.AutoEdits -> ApprovalMode.AutoEdits
        TrustLevel.Full -> ApprovalMode.AutoApprove
        TrustLevel.Ask, null -> ApprovalMode.Ask
    },
)

private fun StudioSessionSettings.native(target: EngineTarget): SessionConfiguration = SessionConfiguration(
    target.model,
    reasoningEffort,
    when (approval) {
        ApprovalMode.Ask -> TrustLevel.Ask
        ApprovalMode.AutoEdits -> TrustLevel.AutoEdits
        ApprovalMode.AutoApprove -> TrustLevel.Full
    },
)

private fun StudioSettingChange.native(target: EngineTarget): SessionConfigurationChange = when (this) {
    is StudioSettingChange.Model -> SessionConfigurationChange.Model(target.model)

    is StudioSettingChange.Effort -> SessionConfigurationChange.Effort(effort)

    is StudioSettingChange.Approval -> SessionConfigurationChange.Trust(
        when (approval) {
            ApprovalMode.Ask -> TrustLevel.Ask
            ApprovalMode.AutoEdits -> TrustLevel.AutoEdits
            ApprovalMode.AutoApprove -> TrustLevel.Full
        },
    )
}

private fun StudioSettingChange.feedback(target: EngineTarget, current: SessionConfiguration): FeedbackChange =
    when (this) {
        is StudioSettingChange.Model -> FeedbackChange.Model(
            target,
            studioModelTarget(modelId) ?: target.copy(model = ModelId(modelId)),
        )

        is StudioSettingChange.Effort -> FeedbackChange.Effort(current.reasoningEffort, effort)

        is StudioSettingChange.Approval -> FeedbackChange.Trust(
            current.trust,
            (native(target) as SessionConfigurationChange.Trust).trust,
        )
    }

private fun StudioSettingChange.matches(target: EngineTarget, current: SessionConfiguration): Boolean = when (this) {
    is StudioSettingChange.Model -> modelId == target.studioModelId()
    is StudioSettingChange.Effort -> effort == current.reasoningEffort
    is StudioSettingChange.Approval -> (native(target) as SessionConfigurationChange.Trust).trust == current.trust
}
