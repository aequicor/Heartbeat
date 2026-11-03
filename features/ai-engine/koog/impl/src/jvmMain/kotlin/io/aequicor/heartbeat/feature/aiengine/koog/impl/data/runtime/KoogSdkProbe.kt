package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.network.OfflineHttpClients
import io.aequicor.heartbeat.core.network.OfflineHttpSession
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TimeMark

/** Uses the ordinary production SDK construction and no profile/vault/session/agent/configuration ports. */
internal class KoogSdkProbe(
    private val offline: OfflineHttpClients,
    private val platform: PlatformInfo,
    private val dispatchers: DispatcherProvider,
) {
    private val log = Log.tag("KoogSdkProbe")

    suspend fun run(deadline: TimeMark, providers: List<KoogProvider> = KoogProvider.entries): KoogSdkReport =
        withContext(dispatchers.io) {
            val receipts = buildList {
                for (provider in providers) {
                    for (check in KoogSdkCheck.entries) {
                        currentCoroutineContext().ensureActive()
                        val receipt = if (deadline.hasPassedNow()) {
                            unobserved(provider, check)
                        } else {
                            examine(provider, check, deadline)
                        }
                        add(receipt)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val confirmed = receipts.count { it.outcome == KoogSdkOutcome.Confirmed }
            val rejected = receipts.count { it.outcome == KoogSdkOutcome.Rejected }
            val unobserved = receipts.count { it.outcome == KoogSdkOutcome.Unobserved }
            log.i {
                "Offline SDK checks finished: confirmed=$confirmed, rejected=$rejected, unobserved=$unobserved"
            }
            KoogSdkReport(sdkBuildInputs(), receipts)
        }

    private suspend fun examine(provider: KoogProvider, check: KoogSdkCheck, deadline: TimeMark): KoogSdkReceipt =
        supervisorScope {
            val owner = offline.open(listOf(sdkExchange(provider, check)))
            val infrastructure = AtomicBoolean(false)
            val worker = async { execute(owner, provider, check, infrastructure) }
            withKoogCleanup(
                cleanup = {
                    withContext(NonCancellable) {
                        worker.cancel()
                        // Also releases a worker stuck in a broken SDK close after the deadline.
                        withKoogCleanup(cleanup = { worker.join() }) { owner.closeAndAwait() }
                    }
                },
            ) {
                val result = withTimeoutOrNull(remaining(deadline)) {
                    if (check == KoogSdkCheck.Cancellation) {
                        cancelAfterDispatch(owner, worker, infrastructure)
                    } else {
                        worker.await()
                    }
                }
                if (result == null) unobserved(provider, check) else receipt(owner, provider, check, result)
            }
        }

    private suspend fun receipt(
        owner: OfflineHttpSession,
        provider: KoogProvider,
        check: KoogSdkCheck,
        result: Observation,
    ): KoogSdkReceipt {
        val progress = owner.progress()
        // Observe before the root owner force-closes anything: it must not conceal leaked SDK children.
        val isReleased = owner.client.engine.coroutineContext.job.children.none {
            it !== owner.client.coroutineContext.job
        }
        val issue = when {
            !progress.isComplete -> KoogSdkIssue.WireMismatch
            !isReleased -> KoogSdkIssue.OwnedJobs
            else -> evaluate(check, result)
        }
        return KoogSdkReceipt(
            provider,
            check,
            if (issue == null) KoogSdkOutcome.Confirmed else KoogSdkOutcome.Rejected,
            issue,
            progress,
            isReleased,
        )
    }

    private suspend fun execute(
        owner: OfflineHttpSession,
        provider: KoogProvider,
        check: KoogSdkCheck,
        infrastructure: AtomicBoolean,
    ): Observation = try {
        val client = SdkKoogTransport(owner.client, platform).open(
            provider,
            if (provider == KoogProvider.Ollama) null else SDK_PROBE_KEY,
            SDK_PROBE_MODEL,
            provider.sdkProbeOrigin,
        )
        withKoogCleanup(cleanup = {
            withContext(NonCancellable) {
                // Observe cleanup directly, before cancellation crosses a coroutine exception-copy boundary.
                infrastructure.set(true)
                client.closeAndAwait()
                infrastructure.set(false)
            }
        }) { observe(client, provider, check) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "Offline SDK infrastructure failed: provider=${provider.name}, check=$check" }
        Observation(isInfrastructureFailed = true)
    }

    private suspend fun observe(client: KoogClient, provider: KoogProvider, check: KoogSdkCheck): Observation = try {
        val input = prompt("heartbeat-sdk-contract", LLMParams(maxTokens = SDK_PROBE_TOKENS)) {
            user(SDK_PROBE_TEXT)
        }
        if (check == KoogSdkCheck.CompletionCodec) {
            val response = client.executor.execute(input, provider.textModel(SDK_PROBE_MODEL))
            Observation(
                isPayloadValid =
                    response.parts.all { it is MessagePart.Text } && response.textContent() == SDK_PROBE_TEXT,
            )
        } else {
            val result = SdkStreamObservation()
            client.executor.executeStreaming(input, provider.textModel(SDK_PROBE_MODEL)).collect {
                currentCoroutineContext().ensureActive()
                result.accept(it)
            }
            result.result(client.observedFinishReason())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SdkProbeOverflow) {
        log.w(e.redactedFailure()) { "Offline SDK observation exceeded its bound" }
        Observation(isFailed = true, isOverflow = true)
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "Offline SDK codec failed: provider=${provider.name}, check=$check" }
        Observation(isFailed = true)
    }

    private suspend fun cancelAfterDispatch(
        owner: OfflineHttpSession,
        worker: Deferred<Observation>,
        infrastructure: AtomicBoolean,
    ): Observation {
        while (!worker.isCompleted && owner.progress().received == 0) yield()
        if (worker.isCompleted) return worker.await()
        worker.cancel()
        worker.join()
        return Observation(
            isCancelled = worker.isCancelled,
            isInfrastructureFailed = infrastructure.get(),
        )
    }
}

private data class Observation(
    val isPayloadValid: Boolean = false,
    val isNormalTerminal: Boolean = false,
    val isFailed: Boolean = false,
    val isOverflow: Boolean = false,
    val isCancelled: Boolean = false,
    val isInfrastructureFailed: Boolean = false,
)

private fun evaluate(check: KoogSdkCheck, result: Observation): KoogSdkIssue? {
    if (result.isInfrastructureFailed) return KoogSdkIssue.Failure
    val isPassed = when (check) {
        KoogSdkCheck.CompletionCodec -> result.isPayloadValid
        KoogSdkCheck.StreamStop -> result.isNormalTerminal && result.isPayloadValid
        KoogSdkCheck.UnterminatedStream -> result.rejectsIncomplete()
        KoogSdkCheck.MalformedStream -> result.isFailed && !result.isOverflow
        KoogSdkCheck.Cancellation -> result.isCancelled
    }
    return if (isPassed) {
        null
    } else {
        when (check) {
            KoogSdkCheck.CompletionCodec -> KoogSdkIssue.Payload
            KoogSdkCheck.StreamStop, KoogSdkCheck.UnterminatedStream -> KoogSdkIssue.Terminal
            KoogSdkCheck.MalformedStream -> KoogSdkIssue.MalformedAccepted
            KoogSdkCheck.Cancellation -> KoogSdkIssue.Failure
        }
    }
}

private fun Observation.rejectsIncomplete(): Boolean {
    val isUnfinishedText = isPayloadValid && !isNormalTerminal
    return !isOverflow && (isFailed || isUnfinishedText)
}

private class SdkStreamObservation {
    private val blocks = mutableMapOf<Int, String>()
    private var frames = 0
    private var terminal: String? = null
    private var isInvalid = false
    private var isEnded = false

    fun accept(frame: StreamFrame) {
        frames++
        if (frames > MAX_SDK_FRAMES) throw SdkProbeOverflow()
        if (isEnded) isInvalid = true
        when (frame) {
            is StreamFrame.TextDelta -> update(frame.index, frame.text, isDelta = true)

            is StreamFrame.TextComplete -> update(frame.index, frame.text, isDelta = false)

            is StreamFrame.End -> {
                isEnded = true
                terminal = frame.finishReason
            }

            is StreamFrame.ReasoningDelta, is StreamFrame.ReasoningComplete,
            is StreamFrame.ToolCallDelta, is StreamFrame.ToolCallComplete,
            -> isInvalid = true
        }
        if (blocks.values.sumOf { it.length } > MAX_SDK_CHARACTERS) throw SdkProbeOverflow()
    }

    private fun update(index: Int?, text: String, isDelta: Boolean) {
        val key = index ?: 0
        if (key < 0) isInvalid = true
        blocks[key] = if (isDelta) blocks[key].orEmpty() + text else text
    }

    fun result(observedFinishReason: String?): Observation = Observation(
        isPayloadValid = !isInvalid && blocks.toSortedMap().values.joinToString("") == SDK_PROBE_TEXT,
        isNormalTerminal = !isInvalid && (observedFinishReason ?: terminal) in setOf("stop", "end_turn"),
    )
}

private class SdkProbeOverflow : IllegalStateException("Offline SDK observation exceeded its bound")
private fun remaining(deadline: TimeMark): Duration = (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO)
private fun unobserved(provider: KoogProvider, check: KoogSdkCheck) =
    KoogSdkReceipt(provider, check, KoogSdkOutcome.Unobserved, KoogSdkIssue.Deadline)
private const val MAX_SDK_FRAMES = 64
private const val MAX_SDK_CHARACTERS = 128
