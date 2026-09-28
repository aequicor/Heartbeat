package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import kotlinx.collections.immutable.toImmutableList

internal fun ResearchScreenState.reflectResearch(state: ResearchChatState): ResearchScreenState = when (state) {
    ResearchChatState.Idle, is ResearchChatState.Loading -> copy(phase = ResearchPhase.Loading)
    is ResearchChatState.Failed -> copy(phase = ResearchPhase.Error)
    ResearchChatState.Disabled -> copy(phase = ResearchPhase.Disabled, isEditable = false)
    is ResearchChatState.Ready -> reflectReady(state)
}

private fun ResearchScreenState.reflectReady(state: ResearchChatState.Ready): ResearchScreenState {
    val session = state.session
    val question = state.question
    val selected = if (session != null && question != null) session.selectedResources(question) else emptyList()
    // Source preparation may be running before any new native content exists; the old answer is complete.
    val isAnswerStreaming = state.isRunning && question != null &&
        question.pendingSegmentStart?.let { it < question.items.size } == true
    return copy(
        phase = ResearchPhase.Ready,
        sessions = state.workspace.sessions.map { ResearchSessionUi(it.id, it.title, it.id == state.sessionId) }
            .toImmutableList(),
        questions = session?.questions.orEmpty().map {
            ResearchQuestionUi(it.id, it.title, it.id == state.questionId, it.id in state.workspace.running)
        }.toImmutableList(),
        resources = session?.resources.orEmpty().asSequence().filter {
            it.id in session?.sharedResourceIds.orEmpty() || it.id in question?.resourceIds.orEmpty()
        }.map {
            ResearchResourceUi(
                it.id,
                it.title,
                if (it.kind == ResearchResourceKind.Website || it.value.startsWith(
                        "https://",
                    )
                ) {
                    it.value
                } else {
                    it.mediaType
                },
                it.kind.toUi(),
                it.id in session?.sharedResourceIds.orEmpty(),
                selected.any { source -> source.id == it.id },
            )
        }.toImmutableList(),
        messages = question?.items.orEmpty().toResearchMessages(isAnswerStreaming),
        sessionTitle = session?.title.orEmpty(),
        questionTitle = question?.title.orEmpty(),
        questionId = question?.id,
        draft = question?.id?.let { drafts[it] }.orEmpty(),
        isRunning = state.isRunning,
        isEditable = state.isEnabled && !state.isMutating && !state.isRunning,
        hasError = state.hasError,
        hasQuestionFailed = question?.hasFailed == true,
    )
}

internal fun ResearchResourceKind.toUi(): ResourceKindUi = when (this) {
    ResearchResourceKind.Website -> ResourceKindUi.Website
    ResearchResourceKind.Document -> ResourceKindUi.Document
    ResearchResourceKind.Image -> ResourceKindUi.Image
}

internal fun ResourceKindUi.toDomain(): ResearchResourceKind = when (this) {
    ResourceKindUi.Website -> ResearchResourceKind.Website
    ResourceKindUi.Document -> ResearchResourceKind.Document
    ResourceKindUi.Image -> ResearchResourceKind.Image
}

internal fun ResourceScopeUi.toDomain(): ResearchResourceScope = when (this) {
    ResourceScopeUi.Session -> ResearchResourceScope.Session
    ResourceScopeUi.Question -> ResearchResourceScope.Question
}
