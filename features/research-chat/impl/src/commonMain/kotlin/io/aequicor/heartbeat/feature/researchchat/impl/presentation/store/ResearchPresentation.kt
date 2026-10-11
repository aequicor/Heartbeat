package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

internal fun ResearchScreenState.reflectResearch(state: ResearchChatState): ResearchScreenState = when (state) {
    ResearchChatState.Idle, is ResearchChatState.Loading,
    -> copy(phase = ResearchPhase.Loading)

    is ResearchChatState.Failed -> copy(phase = ResearchPhase.Error)

    ResearchChatState.Disabled -> copy(phase = ResearchPhase.Disabled, isEditable = false)

    is ResearchChatState.Ready -> reflectReady(state)
}

private fun ResearchScreenState.reflectReady(state: ResearchChatState.Ready): ResearchScreenState {
    val question = state.question
    val selected = state.selectedSources()
    return copy(
        phase = ResearchPhase.Ready,
        permissions = state.permissionRows(),
        sessions = state.sessionRows(),
        questions = state.questionRows(),
        resources = resourceRows(state, selected),
        messages = question?.items.orEmpty().toResearchMessages(state.isAnswerStreaming()),
        sessionTitle = state.session?.title.orEmpty(),
        questionTitle = question?.title.orEmpty(),
        questionId = question?.id,
        draft = question?.id?.let { drafts[it] }.orEmpty(),
        isRunning = state.isRunning,
        areSourcesWithinLimits = sourcesWithinLimits(selected),
        isEditable = state.isEnabled && !state.isMutating && !state.isRunning,
        hasError = state.hasError,
        hasQuestionFailed = question?.hasFailed == true,
    )
}

private fun ResearchChatState.Ready.selectedSources(): List<ResearchResource> {
    val question = question ?: return emptyList()
    return session?.selectedResources(question).orEmpty()
}

/** Source preparation has no new native content yet; the previous answer remains complete. */
private fun ResearchChatState.Ready.isAnswerStreaming(): Boolean {
    val question = question ?: return false
    return isRunning && question.pendingSegmentStart?.let { it < question.items.size } == true
}

private fun ResearchChatState.Ready.permissionRows(): ImmutableList<ResearchPermissionUi> =
    workspace.permissions[questionId].orEmpty().map { it.toPermissionUi() }.toImmutableList()

private fun ResearchChatState.Ready.sessionRows(): ImmutableList<ResearchSessionUi> =
    workspace.sessions.map { ResearchSessionUi(it.id, it.title, it.id == sessionId) }.toImmutableList()

private fun ResearchChatState.Ready.questionRows(): ImmutableList<ResearchQuestionUi> =
    session?.questions.orEmpty().map {
        ResearchQuestionUi(it.id, it.title, it.id == questionId, it.id in workspace.running)
    }.toImmutableList()

private fun ResearchScreenState.resourceRows(
    state: ResearchChatState.Ready,
    selected: List<ResearchResource>,
): ImmutableList<ResearchResourceUi> {
    val sharedIds = state.session?.sharedResourceIds.orEmpty()
    val questionIds = state.question?.resourceIds.orEmpty()
    val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
    return state.session?.resources.orEmpty().asSequence()
        .filter { it.id in sharedIds || it.id in questionIds }
        .map { source ->
            toResourceUi(
                source,
                isShared = source.id in sharedIds,
                isSelected = source.id in selectedIds,
                isBrowserRecoveryAvailable = state.workspace.isBrowserRecoveryAvailable,
            )
        }.toImmutableList()
}

private fun ResearchScreenState.toResourceUi(
    source: ResearchResource,
    isShared: Boolean,
    isSelected: Boolean,
    isBrowserRecoveryAvailable: Boolean,
): ResearchResourceUi = with(source) {
    val isCompatible = !hasAttachmentError &&
        (kind == ResearchResourceKind.Website || attachmentSupport.accepts(mediaType))
    ResearchResourceUi(
        id = id,
        title = title,
        detail = if (kind == ResearchResourceKind.Website || value.startsWith("https://")) {
            value
        } else {
            mediaType
        },
        kind = kind.toUi(),
        isShared = isShared,
        isSelected = isSelected,
        attachmentId = attachmentId?.value,
        isCompatible = isCompatible,
        sizeBytes = attachmentSizeBytes,
        hasImportError = hasAttachmentError,
        isBrowserRecoveryAvailable = isBrowserRecoveryAvailable,
        loadError = loadError,
        isBrowserRequired =
            loadState in
                setOf(
                    io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState.Failed,
                    io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
                        .NeedsUserAction,
                    io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState.Loading,
                ),
        isBrowserOpened = browserTabId != null,
        isLoadingSource =
            loadState ==
                io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState.Loading,
        mediaType = mediaType,
    )
}

private fun ResearchScreenState.sourcesWithinLimits(sources: List<ResearchResource>): Boolean {
    val files = sources.filter { it.kind != ResearchResourceKind.Website }
    return files.size <= attachmentSupport.maxAttachments &&
        files.sumOf { it.attachmentSizeBytes ?: 0L } <= attachmentSupport.maxTotalBytes &&
        files.all { (it.attachmentSizeBytes ?: 0L) <= attachmentSupport.maxFileBytes }
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
