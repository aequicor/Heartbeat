package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aistudio.api.StudioQuestionnaireAdmission
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime

/** Studio owns questionnaire sources; unknown chats fail closed through the runtime's durable revocation. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class StudioQuestionnaireManualAdmission(private val runtime: Lazy<StudioRuntime>) :
    StudioQuestionnaireAdmission {
    override suspend fun revoke(source: String) {
        runtime.value.invalidateUserInstructions(source)
    }
}
