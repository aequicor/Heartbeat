package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent assessment of the complete proposed change. No source or assessment text is logged. */
internal fun interface HarnessCodeReviewer {
    suspend fun review(target: EngineTarget, change: String): CodeReview
}

/** A positive verdict requires a complete, explicit assessment, never a substring in free-form output. */
internal data class CodeReview(val verdict: String, val reason: String) {
    val isSafe: Boolean get() = verdict == "safe" && reason.isNotBlank()
    override fun toString(): String = "CodeReview(verdict=$verdict)"
}

/**
 * One bounded memo per authoring contribution. The key covers the trusted caller, model, exact code and library
 * revision. Repeated gate reads reuse the same assessment; execution only reads it, never starts another review.
 * Serial assessment prevents a burst of tool calls from opening unbounded reviewer sessions.
 */
internal class HarnessCodeReviews(private val reviewer: HarnessCodeReviewer) {
    private val log = Log.tag("HarnessCodeReviews")
    private val lock = Mutex()
    private val memo = linkedMapOf<String, CodeReview>()

    suspend fun check(key: String, target: EngineTarget?, change: String, canStart: Boolean): CodeReview? =
        lock.withLock {
            memo[key]?.let { return@withLock it }
            if (!canStart) return@withLock null
            val result = if (target == null) {
                CodeReview("uncertain", "Не удалось определить модель для автоматического ревью.")
            } else {
                try {
                    reviewer.review(target, change)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Provider exceptions can contain source fragments; record only their class.
                    log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Harness safety review unavailable" }
                    CodeReview("uncertain", "Автоматическая проверка безопасности недоступна. Повторите позже.")
                }
            }
            if (memo.size >= REVIEW_MEMO) memo.remove(memo.keys.first())
            memo[key] = result
            log.i { "Harness assessed safe=${result.isSafe}" }
            result
        }
}

private const val REVIEW_MEMO = 16
