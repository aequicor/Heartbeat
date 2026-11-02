package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.KeyValueReadPolicy
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.jsonKey
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.api.StudioCorrection
import io.aequicor.heartbeat.feature.aistudio.api.StudioMessageQueueEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioMessageQueues
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueEffect
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueNotice
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueOutput
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueueState
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.api.studioQueueMachineSpec
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** A captured lease and turn, never looked up again after a steering row was admitted. */
internal data class StudioSteeringTarget(
    val session: ActiveSession,
    val turn: TurnId,
    val chat: String,
    val isNative: Boolean = true,
    val request: String? = null,
)

/** Native execution boundary implemented by the existing profile repository. */
internal interface StudioQueueHost {
    suspend fun queuedRun(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        submission: StudioSubmissionGate,
        accepted: suspend () -> Unit,
    )

    suspend fun steeringTarget(chat: String): StudioSteeringTarget
    suspend fun steerQueued(target: StudioSteeringTarget, message: StudioQueuedMessage)
    suspend fun interrupt(target: StudioSteeringTarget) = target.interrupt()
}

/**
 * Owns the queue independently of all studio screens. The command mutex is held only until a durable mutation,
 * never while waiting for a native turn. Execution effects are handed to the profile before returning.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class StudioMessageQueueOwner(
    @ForScope(ProfileScope::class) stores: DataStores,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val launcher: MachineLauncher,
    private val host: Lazy<StudioQueueHost>,
    private val runs: StudioRunCoordinator,
    private val toggles: FeatureToggles,
) : StudioQueuePort {
    private val log = Log.tag("StudioMessageQueue")
    private val store = stores.keyValue(QueueSpec)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Machine<StudioQueueState, StudioQueueIntent, StudioQueueOutput>>()
    private val mutableState = MutableStateFlow(StudioMessageQueues())
    override val state: StateFlow<StudioMessageQueues> = mutableState.asStateFlow()
    override val notices = MutableStateFlow<Map<String, StudioQueueNotice>>(emptyMap())
    private val environments = mutableMapOf<String, StudioChatEnvironment>()
    private val targets = mutableMapOf<String, StudioSteeringTarget>()
    private val blocked = MutableStateFlow<Set<String>>(emptySet())

    init {
        profile.coroutineScope.launch {
            try {
                val stored = store.get(QueueKey) ?: StudioMessageQueues()
                val restored = stored.copy(chats = stored.chats.mapValues { it.value.restored() })
                val machine = launcher.launch(
                    studioQueueMachineSpec(restored),
                    profile,
                    EffectHandler { effect, feedback ->
                        when (effect) {
                            is StudioQueueEffect.Save -> {
                                store.set(QueueKey, effect.data)
                                feedback.send(StudioQueueIntent.Internal.Stored(effect.operation, effect.next))
                            }

                            StudioQueueEffect.Wake -> {
                                profile.coroutineScope.launch { drain() }
                                Unit
                            }

                            is StudioQueueEffect.Dispatch -> dispatch(effect)

                            is StudioQueueEffect.Steer -> steer(effect)

                            is StudioQueueEffect.Interrupt -> interrupt(effect)
                        }
                    },
                )
                machine.send(StudioQueueIntent.Internal.Availability(toggles.get(StudioMessageQueueEnabled)))
                ready.complete(machine)
                machine.state.collect { mutableState.value = it.durable }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.e(error) { "Could not load the human message queue" }
                ready.completeExceptionally(error)
            }
        }
        profile.coroutineScope.launch {
            runs.occupied.collect { busy ->
                mutate { machine -> machine.send(StudioQueueIntent.Internal.Activity(busy, busy)) }
            }
        }
        profile.coroutineScope.launch {
            toggles.observe(StudioMessageQueueEnabled).collect { enabled ->
                mutate { it.send(StudioQueueIntent.Internal.Availability(enabled)) }
            }
        }
    }

    override fun hasPending(chat: String): Boolean = mutableState.value.chat(chat).messages.isNotEmpty()

    /** Native acceptance of this profile command survives the lifetime of its screen waiter. */
    override suspend fun enqueue(
        chat: String,
        message: StudioQueuedMessage,
        environment: StudioChatEnvironment?,
        isCorrection: Boolean,
    ) = profile.coroutineScope.async {
        mutex.withLock {
            val machine = ready.await()
            environment?.let { environments[message.id] = it }
            machine.send(StudioQueueIntent.Internal.Activity(runs.occupied.value, runs.occupied.value))
            commit(machine, StudioQueueIntent.Public.Enqueue(chat, message, token()), tokenOf = { it.operation })
            // From this commit onward the profile owns the input, even if the requested follow-up action fails.
            try {
                if (isCorrection) {
                    captureCorrection(machine, chat, message.id)
                } else if (machine.state.value.data.chat(chat).isPaused) {
                    commit(machine, StudioQueueIntent.Public.Resume(chat, token()), tokenOf = { it.operation })
                }
                if (!isCorrection) blocked.update { it - chat }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Queue action failed after durable input admission" }
                blocked.update { it + chat }
                pauseCapturedInput(machine, chat)
                notice(chat, false)
            }
        }
    }.await()

    /** A second write cannot turn a successful capture into a composer rejection. */
    private suspend fun pauseCapturedInput(
        machine: Machine<StudioQueueState, StudioQueueIntent, StudioQueueOutput>,
        chat: String,
    ) {
        if (machine.state.value.data.chat(chat).isPaused) return
        try {
            commit(machine, StudioQueueIntent.Public.Pause(chat, token()), tokenOf = { it.operation })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The in-memory barrier still blocks delivery; restoration always pauses durable queues too.
            log.e(error) { "Could not persist a pause after durable input admission" }
        }
    }

    override suspend fun correct(chat: String, message: String) {
        try {
            mutate { machine ->
                captureCorrection(machine, chat, message)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Correction refused before native submission" }
            notice(chat, false)
        }
    }

    private suspend fun captureCorrection(
        machine: Machine<StudioQueueState, StudioQueueIntent, StudioQueueOutput>,
        chat: String,
        message: String,
    ) {
        val target = host.value.steeringTarget(chat)
        val key = if (target.isNative) message else token()
        check(key !in targets) { "Correction already admitted" }
        targets[key] = target
        var isAdmitted = false
        try {
            if (target.isNative) {
                commit(machine, StudioQueueIntent.Public.Steer(chat, message, token()), tokenOf = { it.operation })
            } else {
                val correction = StudioCorrection(message, key, checkNotNull(target.request))
                commit(
                    machine,
                    StudioQueueIntent.Public.Interrupt(chat, correction, token()),
                    tokenOf = { it.operation },
                )
                log.i { "Reserve correction awaiting interruption" }
                blocked.update { it - chat }
            }
            isAdmitted = true
        } finally {
            if (!isAdmitted) targets.remove(key)
        }
    }

    private fun interrupt(effect: StudioQueueEffect.Interrupt) {
        profile.coroutineScope.launch {
            try {
                val target = checkNotNull(targets.remove(effect.correction.token)) { "Correction target was lost" }
                host.value.interrupt(target)
                mutate { machine ->
                    commit(
                        machine,
                        StudioQueueIntent.Internal.Interrupted(effect.chat, effect.correction.token, token()),
                        tokenOf = { it.operation },
                        mayIgnore = true,
                    )
                }
            } catch (error: TimeoutCancellationException) {
                log.w(error) { "Correction interruption timed out" }
                interruptionFailed(effect, error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Correction interruption failed" }
                interruptionFailed(effect, error)
            }
        }
    }

    private suspend fun interruptionFailed(effect: StudioQueueEffect.Interrupt, error: Exception) {
        log.w(error) { "Correction retained because interruption was not confirmed" }
        mutate { machine ->
            if (machine.state.value.data.chat(effect.chat).correction?.token != effect.correction.token) return@mutate
            blocked.update { it + effect.chat }
            commit(machine, StudioQueueIntent.Public.Pause(effect.chat, token()), tokenOf = { it.operation })
            notice(effect.chat, false)
        }
    }

    override suspend fun remove(chat: String, message: String) = mutate { machine ->
        commit(machine, StudioQueueIntent.Public.Remove(chat, message, token()), tokenOf = { it.operation })
        environments.remove(message)
        targets.remove(message)
        Unit
    }

    override suspend fun pause(chat: String) {
        log.i { "Pause automatic input before cancellation" }
        blocked.update { it + chat }
        mutate { machine ->
            commit(machine, StudioQueueIntent.Public.Pause(chat, token()), tokenOf = { it.operation })
        }
    }

    override suspend fun resume(chat: String) = mutate { machine ->
        log.i { "Resume automatic queued input" }
        commit(machine, StudioQueueIntent.Public.Resume(chat, token()), tokenOf = { it.operation })
        blocked.update { it - chat }
        machine.send(StudioQueueIntent.Internal.Activity(runs.occupied.value, runs.occupied.value))
        Unit
    }

    override suspend fun settled(chat: String, request: String, outcome: RunOutcome, isSubmitted: Boolean) =
        mutate { machine ->
            log.i { "Settle input reservation outcome=$outcome" }
            if (!isSubmitted && machine.state.value.data.chat(chat).activeRequest != request) return@mutate
            val correction = machine.state.value.data.chat(chat).correction
            if (outcome != RunOutcome.Completed &&
                !(outcome == RunOutcome.Stopped && correction?.request == request)
            ) {
                blocked.update { it + chat }
            }
            if (machine.state.value.data.chat(chat).messages.isNotEmpty() ||
                machine.state.value.data.chat(chat).activeRequest != null
            ) {
                commit(
                    machine,
                    StudioQueueIntent.Internal.Settled(chat, request, outcome, token(), isSubmitted),
                    tokenOf = { it.operation },
                )
            }
        }

    override fun reportFailure(chat: String) = notice(chat, false)

    override fun dismiss(chat: String, notice: String) {
        log.i { "Dismiss transient queue feedback" }
        notices.update { if (it[chat]?.id == notice) it - chat else it }
    }

    private suspend fun drain() = mutate { machine ->
        for (chat in machine.state.value.data.chats.keys) {
            if (chat in blocked.value) continue
            val command = StudioQueueIntent.Public.Drain(chat, token())
            commit(machine, command, tokenOf = { it.operation }, mayIgnore = true)
        }
    }

    private fun dispatch(effect: StudioQueueEffect.Dispatch) {
        profile.coroutineScope.launch {
            try {
                val environment = environments[effect.message.id]
                check(!effect.message.hasEnvironment || environment != null) { "Host environment was lost" }
                val machine = ready.await()
                val admission = combine(machine.state, blocked) { snapshot, paused ->
                    val queue = snapshot.data.chat(effect.chat)
                    val isAllowed = snapshot.isEnabled && effect.chat !in paused
                    if (isAllowed &&
                        !queue.isPaused && queue.activeRequest == effect.message.id
                    ) {
                        ScheduledWakeAdmission.Allow
                    } else {
                        ScheduledWakeAdmission.Drop
                    }
                }.distinctUntilChanged()
                host.value.queuedRun(effect.chat, effect.message, environment, StudioRunSubmission(admission)) {
                    accepted(effect.chat, effect.message.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Queued message delivery failed" }
                failed(effect.chat, effect.message.id, error)
            }
        }
    }

    private fun steer(effect: StudioQueueEffect.Steer) {
        profile.coroutineScope.launch {
            try {
                val target = checkNotNull(targets.remove(effect.message.id)) { "Steering target was lost" }
                host.value.steerQueued(target, effect.message)
                if (accepted(effect.chat, effect.message.id)) notice(effect.chat, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log.w(error) { "Queued correction was not acknowledged" }
                failed(effect.chat, effect.message.id, error)
            }
        }
    }

    private suspend fun accepted(chat: String, message: String): Boolean = try {
        mutate { machine ->
            commit(machine, StudioQueueIntent.Internal.Accepted(chat, message, token()), tokenOf = { it.operation })
            environments.remove(message)
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // The native turn is already owned by the profile. A journal failure must not cancel that turn.
        log.e(error) { "Accepted message could not be removed durably; automatic replay stays blocked" }
        blocked.update { it + chat }
        notice(chat, false, true)
        false
    }

    private suspend fun failed(chat: String, message: String, error: Exception) {
        val failure = (error as? EngineException)?.failure
        val isUncertain = failure == EngineFailure.Request(RequestFailureReason.OutcomeUnknown) ||
            (failure is EngineFailure.Request && failure.reason == RequestFailureReason.OutcomeUnknown)
        log.w { "Preserve failed input isUncertain=$isUncertain" }
        if (isUncertain) blocked.update { it + chat }
        mutate { machine ->
            commit(
                machine,
                StudioQueueIntent.Internal.Failed(chat, message, isUncertain, token()),
                tokenOf = { it.operation },
            )
        }
        notice(chat, false, isUncertain)
    }

    private fun notice(chat: String, success: Boolean, isUncertain: Boolean = false) {
        log.i { "Publish queue feedback success=$success isUncertain=$isUncertain" }
        notices.update { it + (chat to StudioQueueNotice(token(), success, isUncertain)) }
    }

    private suspend fun <T> mutate(
        body: suspend (Machine<StudioQueueState, StudioQueueIntent, StudioQueueOutput>) -> T,
    ): T = profile.coroutineScope.async { mutex.withLock { body(ready.await()) } }.await()

    private suspend fun <I : StudioQueueIntent> commit(
        machine: Machine<StudioQueueState, StudioQueueIntent, StudioQueueOutput>,
        intent: I,
        tokenOf: (I) -> String,
        mayIgnore: Boolean = false,
    ) = coroutineScope {
        val operation = tokenOf(intent)
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            machine.outputs.first { it.operation == operation }
        }
        if (machine.send(intent) != SendResult.Accepted) {
            answer.cancel()
            check(mayIgnore) { "Queue command refused" }
        } else {
            check(answer.await().isStored) { "Queue persistence failed" }
            log.v { "Publish committed queue snapshot" }
            mutableState.value = machine.state.value.durable
        }
    }

    private fun token() = Uuid.random().toString()
}

private val QueueSpec = KeyValueSpec("studio_message_queue", readPolicy = KeyValueReadPolicy.Strict)
private val QueueKey = jsonKey("queues", StudioMessageQueues.serializer())
