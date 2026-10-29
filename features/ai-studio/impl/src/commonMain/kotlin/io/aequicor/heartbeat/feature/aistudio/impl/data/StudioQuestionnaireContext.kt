package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioQuestionnaireHumanContext
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationContext
import io.aequicor.heartbeat.feature.autocontinue.api.DialogAuthor
import io.aequicor.heartbeat.feature.autocontinue.api.DialogEntry
import kotlinx.coroutines.flow.first

/**
 * Questionnaire generation input and the exact human context used to build it. Before delivering a generated answer,
 * compare [humanContext] with a fresh [StudioRepository.questionnaireHumanContext] snapshot and recheck eligibility.
 * This context never grants tool authority and may survive its revocation after an automatic answer.
 */
internal data class StudioQuestionnaireContext(
    val context: ContinuationContext,
    val humanContext: StudioQuestionnaireHumanContext.Complete,
)

/**
 * Only durable human originals define the task. Transcript prompts have no reliable provenance and are omitted,
 * including prompts without an autopilot mark. Agent answer text follows the originals as auxiliary context;
 * tools, reasoning and generated prompts never become human instructions. Original text is never trimmed or truncated.
 * Missing or blank human context declines generation before reading the transcript.
 * Storage errors and cancellation propagate.
 */
internal suspend fun buildStudioQuestionnaireContext(
    repository: StudioRepository,
    sessionId: String,
    target: EngineTarget,
): StudioQuestionnaireContext? {
    val snapshot = repository.questionnaireHumanContext(sessionId) as? StudioQuestionnaireHumanContext.Complete
        ?: return null
    val human = snapshot.messages.filter { it.text.isNotBlank() }.map { DialogEntry(DialogAuthor.Human, it.text) }
    if (human.isEmpty()) return null
    val auxiliary = repository.observeMessages(sessionId).first().mapNotNull { message ->
        (message as? StudioMessage.Reply)?.text?.takeIf(String::isNotBlank)?.let { DialogEntry(DialogAuthor.Agent, it) }
    }
    return StudioQuestionnaireContext(ContinuationContext(target, human + auxiliary), snapshot)
}
