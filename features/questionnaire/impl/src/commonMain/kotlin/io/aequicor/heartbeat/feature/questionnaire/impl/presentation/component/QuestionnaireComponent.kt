package io.aequicor.heartbeat.feature.questionnaire.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireModel

/** Navigation entry of a source's questions; the host that opened it decides when to replace it. */
@AssistedInject
class QuestionnaireComponent internal constructor(
    @Assisted context: ComponentContext,
    internal val model: QuestionnaireModel,
) : ComponentContext by context {
    /** Creates the component within its retained graph. */
    @AssistedFactory
    fun interface Factory {
        /** Binds Decompose. */
        fun create(context: ComponentContext): QuestionnaireComponent
    }
}
