package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireAnswerContext

/** Captured browser origin of an accepted answer, retained until its profile-owned follow-up finishes. */
internal class StudioQuestionContext(val environment: StudioChatEnvironment) : QuestionnaireAnswerContext
