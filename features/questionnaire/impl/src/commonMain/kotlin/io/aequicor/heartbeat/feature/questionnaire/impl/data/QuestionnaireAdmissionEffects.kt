package io.aequicor.heartbeat.feature.questionnaire.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioQuestionnaireAdmission
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEffect
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source persistence precedes the machine's final admission and output, even without an output collector. */
@Inject
internal class QuestionnaireAdmissionEffects(private val admission: Lazy<StudioQuestionnaireAdmission>) :
    EffectHandler<QuestionnaireEffect, QuestionnaireIntent> {
    private val log = Log.tag("QuestionnaireAdmission")

    override suspend fun handle(effect: QuestionnaireEffect, machine: EffectScope<QuestionnaireIntent>) {
        when (effect) {
            is QuestionnaireEffect.PrepareManual -> {
                log.d { "Revoke human context before accepting a manual answer" }
                admission.value.revoke(effect.questionnaire.source)
                currentCoroutineContext().ensureActive()
                machine.send(QuestionnaireIntent.Internal.ManualPrepared(effect))
            }
        }
    }
}
