package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckAspect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckProblem

/** Label-free, exact auth context. No secret resolution, credential-helper execution or model IO occurs here. */
internal fun AuthSource.modelCheckContext(): AuthSource = withoutLabel()

/**
 * Fixed host probes and deterministic assessment. Common visible link/credential requests are warnings, not a
 * universal phishing detector. Passing the accuracy task proves only these three observations, never general truth.
 */
internal object ModelCheckEvaluation {
    const val RESPONSE_PROBE = "Reply with the single word READY. Do not request credentials or visit a website."
    const val RESPONSE_EXPECTED = "A completed non-empty text answer without navigation or credential requests."
    const val ACCURACY_PROBE = """Answer three questions using only the data below.
1. What is 7 + 12?
2. What is the exact value of label in the data?
3. What is the launch date? If the data does not give it, answer ABSENT. Do not guess.
<data>
label: cedar
</data>
Return one line with the three answers separated by | and nothing else."""
    const val ACCURACY_EXPECTED = "19|cedar|ABSENT (spaces around separators are permitted)"

    fun response(answer: String): ModelCheckAspect = evaluate(RESPONSE_PROBE, RESPONSE_EXPECTED, answer) {
        ModelCheckOutcome.Passed
    }

    fun accuracy(answer: String): ModelCheckAspect = evaluate(ACCURACY_PROBE, ACCURACY_EXPECTED, answer) {
        val values = answer.trim().split('|').map(String::trim)
        if (values == listOf("19", "cedar", "ABSENT")) {
            ModelCheckOutcome.Passed
        } else {
            ModelCheckOutcome.Failed(ModelCheckProblem.UnexpectedAnswer)
        }
    }

    fun incomplete(isAccuracy: Boolean, reason: ModelCheckIncomplete): ModelCheckAspect = ModelCheckAspect(
        probe = if (isAccuracy) ACCURACY_PROBE else RESPONSE_PROBE,
        expected = if (isAccuracy) ACCURACY_EXPECTED else RESPONSE_EXPECTED,
        answer = null,
        outcome = ModelCheckOutcome.Incomplete(reason),
    )

    private fun evaluate(
        probe: String,
        expected: String,
        answer: String,
        assess: () -> ModelCheckOutcome,
    ): ModelCheckAspect {
        val outcome = when {
            answer.length > ModelCheckAspect.MAX_ANSWER_CHARS ->
                ModelCheckOutcome.Incomplete(ModelCheckIncomplete.ResponseTooLarge)

            answer.none { !it.isWhitespace() && !it.isISOControl() && it.category != CharCategory.FORMAT } ->
                ModelCheckOutcome.Failed(ModelCheckProblem.EmptyAnswer)

            LINK.containsMatchIn(answer) -> ModelCheckOutcome.Failed(ModelCheckProblem.SuspiciousLink)

            requestsSecret(answer) -> ModelCheckOutcome.Failed(ModelCheckProblem.RequestsSecret)

            else -> assess()
        }
        return ModelCheckAspect(probe, expected, answer.take(ModelCheckAspect.MAX_ANSWER_CHARS), outcome)
    }
}

private fun requestsSecret(answer: String): Boolean = SECRET_REQUEST.findAll(answer).any { request ->
    val prefix = answer.substring(0, request.range.first).takeLast(NEGATION_CONTEXT_CHARS)
    !NEGATED_REQUEST.containsMatchIn(prefix)
}

private val LINK = Regex("(?:[a-z][a-z0-9+.-]{1,20}://|www\\.|mailto:|javascript:)", RegexOption.IGNORE_CASE)
private val SECRET_REQUEST = Regex(
    "(?:^|[\\s:,.!?])(?:send|share|give|paste|provide|enter|пришлите|отправьте|введите|передайте|делитесь|дайте)" +
        "(?=[\\s:])(?=[\\s\\S]{0,80}(?:password|api[ _-]?key|token|парол|токен|ключ))",
    RegexOption.IGNORE_CASE,
)
private val NEGATED_REQUEST = Regex(
    "(?:^|\\s)(?:(?:do\\s+not|don't|never)(?:\\s+ever)?|не|никогда)\\s*$",
    RegexOption.IGNORE_CASE,
)
private const val NEGATION_CONTEXT_CHARS = 32
