package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession

/** Changes one question's selection without changing another question or the shared resource. */
internal fun ResearchSession.selectSource(
    questionId: String,
    resourceId: String,
    isSelected: Boolean,
): ResearchSession {
    require(resources.any { it.id == resourceId }) { "Unknown resource" }
    return copy(
        questions =
            questions.map { question ->
                if (question.id != questionId) {
                    question
                } else {
                    question.copy(
                        excludedResourceIds =
                            if (isSelected) {
                                question.excludedResourceIds - resourceId
                            } else {
                                question.excludedResourceIds + resourceId
                            },
                        resourceIds =
                            if (isSelected && resourceId !in sharedResourceIds) {
                                question.resourceIds + resourceId
                            } else {
                                question.resourceIds
                            },
                    )
                }
            },
    )
}
