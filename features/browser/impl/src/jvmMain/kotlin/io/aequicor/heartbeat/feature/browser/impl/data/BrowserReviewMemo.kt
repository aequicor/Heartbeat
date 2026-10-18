package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.reviewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Bounded, single-use verdicts of the independent safety review of one browser's tools. A verdict belongs to the
 * binding of one exact proposal; execution consumes it and never starts another review. [subject] names the browser
 * in logs, which never carry the reviewed change or the reviewer's reason.
 */
internal class BrowserReviewMemo(
    private val reviewer: AgentSafetyReviewer,
    private val policy: String,
    private val subject: String,
    tag: String,
) {
    private val log = Log.tag(tag)
    private val lock = Mutex()
    private val decisions = linkedMapOf<String, AgentSafetyReview>()

    /** The cached verdict of [approval]'s binding, else a new review of [change] by the host's review model. */
    suspend fun authorize(
        context: AgentToolContext,
        approval: AgentToolApproval,
        change: String,
    ): AgentToolAutomaticAuthorization {
        val key = requireNotNull(approval.binding)
        // The lock guards only the memo: reviews of different proposals wait in the reviewer's own bounded queue.
        val review = lock.withLock { decisions[key] } ?: remember(key, assess(context, change))
        return if (review.isSafe) {
            AgentToolAutomaticAuthorization.Allowed(approval)
        } else {
            AgentToolAutomaticAuthorization.Denied("Automatic safety review refused the action: ${review.reason}")
        }
    }

    /** Whether [context] carries exactly [approval] and its safe verdict was still unspent; spends it. */
    suspend fun consume(context: AgentToolContext, approval: AgentToolApproval): Boolean = lock.withLock {
        context.authorization == approval && decisions.remove(approval.binding)?.isSafe == true
    }

    /** A concurrent review of the same proposal that stored first wins, so every verdict matches the memo. */
    private suspend fun remember(key: String, assessed: AgentSafetyReview): AgentSafetyReview = lock.withLock {
        decisions[key] ?: assessed.also {
            if (decisions.size >= MAX_DECISIONS) decisions.remove(decisions.keys.first())
            decisions[key] = it
        }
    }

    private suspend fun assess(context: AgentToolContext, change: String): AgentSafetyReview {
        val target = context.reviewModel ?: return AgentSafetyReview("uncertain", "The current model is unavailable.")
        return try {
            reviewer.review(target, policy, change).also {
                log.i { "$subject operation assessed safe=${it.isSafe}" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "$subject safety review unavailable" }
            AgentSafetyReview("uncertain", "Automatic safety review is unavailable; retry later.")
        }
    }

    private companion object {
        const val MAX_DECISIONS = 32
    }
}

/**
 * The binding of a reviewed proposal: a hash of the trusted caller identity (session, workspace, turn, request,
 * call, engine target and review model), the original approval, the arguments, the [change] shown to the reviewer
 * and the review [policy]. The model cannot supply any of them. [state] is what the verdict stays bound to
 * (see [io.aequicor.heartbeat.feature.browser.impl.domain.reviewedBrowserState]); null binds the whole [change].
 */
internal fun reviewBinding(
    prefix: String,
    context: AgentToolContext,
    original: AgentToolApproval,
    arguments: JsonObject,
    change: String,
    policy: String,
    state: JsonObject? = null,
): String {
    val identity = buildJsonObject {
        put("session", Json.encodeToJsonElement(context.session))
        put("workspace", context.workspace?.value)
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put(
            "original",
            Json.encodeToJsonElement(listOf(original.name, original.title, original.description, original.binding)),
        )
        put("arguments", arguments)
        put("change", state?.toString() ?: change)
        put("policy", policy)
    }.toString()
    val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).toHexString()
    return "$prefix:$hash"
}
