package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json

/** Independent assessment of the complete proposed change. No source or assessment text is logged. */
internal fun interface HarnessCodeReviewer {
    suspend fun review(target: EngineTarget, change: String): CodeReview
}

/** A positive verdict requires a complete, explicit assessment, never a substring in free-form output. */
@Serializable
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
                    log.w(IllegalStateException(e::class.simpleName.orEmpty())) { "Script review unavailable" }
                    CodeReview("uncertain", "Автоматическое ревью недоступно. Требуется ваше решение.")
                }
            }
            if (memo.size >= REVIEW_MEMO) memo.remove(memo.keys.first())
            memo[key] = result
            log.i { "Script assessed safe=${result.isSafe}" }
            result
        }
}

/** Rejects missing fields, unknown verdicts, prose, fences and truncated or oversized answers. */
internal fun parseCodeReview(answer: String): CodeReview {
    if (answer.length > MAX_REVIEW_ANSWER) return invalidReview()
    val review = try {
        Json.decodeFromString(UniqueCodeReview, answer)
    } catch (e: IllegalArgumentException) {
        reviewLog.w(
            IllegalArgumentException(e::class.simpleName.orEmpty()),
        ) { "Script review did not match the schema" }
        return invalidReview()
    }
    return review.takeIf {
        it.verdict in setOf("safe", "risk", "uncertain") && it.reason.isNotBlank() && it.reason.length <= MAX_REASON
    } ?: invalidReview()
}

private fun invalidReview() = CodeReview("uncertain", "Автоматическое ревью не дало однозначного вердикта.")
private val reviewLog = Log.tag("HarnessCodeReview")
private const val REVIEW_MEMO = 16
private const val MAX_REVIEW_ANSWER = 4096
private const val MAX_REASON = 2000

/** Reads fields directly: a JSON tree would already have discarded duplicate keys. */
private object UniqueCodeReview : KSerializer<CodeReview> {
    override val descriptor = buildClassSerialDescriptor("UniqueCodeReview") {
        element<String>("verdict")
        element<String>("reason")
    }

    override fun deserialize(decoder: Decoder): CodeReview = decoder.decodeStructure(descriptor) {
        val fields = mutableMapOf<Int, String>()
        while (true) {
            val index = decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (index !in 0..1 || index in fields) throw SerializationException("Ambiguous review fields")
            fields[index] = decodeStringElement(descriptor, index)
        }
        CodeReview(
            fields[0] ?: throw SerializationException("Missing verdict"),
            fields[1] ?: throw SerializationException("Missing reason"),
        )
    }

    override fun serialize(encoder: Encoder, value: CodeReview) =
        throw SerializationException("Review decoder is read-only")
}
