@file:UseSerializers(ImmutableSetSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant

/** Passive pane messages users may hide without changing execution or answering a pending decision. */
@Serializable
@SerialName("StudioNoticeUi")
enum class StudioNoticeUi {
    SubmitFailed,
    ProjectFailed,
    AttachmentFailed,
    HarnessFailed,
    StopFailed,
    DiagramDraft,
    ConnectModel,
    ProjectModel,
    NativeChild,
    ReadOnly,
    StopUnsupported,
}

/** A dismissal belongs to this pane's displayed context; model hints additionally track the available choices. */
@Serializable
@SerialName("StudioNoticeDismissalUi")
@Immutable
data class StudioNoticeDismissalUi(
    val paneId: Int,
    val notice: StudioNoticeUi,
    val sessionId: String?,
    val projectId: String?,
    val subSession: String,
    val modelId: String? = null,
    val modelIds: ImmutableSet<String> = persistentSetOf(),
    val runStartedAt: Instant? = null,
)

/** The still-current presentation dismissals of [paneId]; stored failure flags remain owned by their machine. */
fun AiStudioScreenState.dismissedNotices(paneId: Int): ImmutableSet<StudioNoticeUi> = noticeDismissals
    .filter { it.paneId == paneId && isCurrent(it) }.map { it.notice }.toImmutableSet()

/**
 * Local messages are consumed directly, so an identical later failure appears again. Mirrored failures and
 * passive status hints keep a presentation-only dismissal, leaving machine state and execution unchanged.
 */
fun AiStudioScreenState.dismissNotice(intent: AiStudioScreenIntent.DismissNotice): AiStudioScreenState {
    val target = intent.target
    val pane = panes.firstOrNull { it.id == target.paneId } ?: return this
    if (target != noticeDismissal(pane, target.notice)) return this
    return when (target.notice) {
        StudioNoticeUi.SubmitFailed -> copy(failedPanes = (failedPanes - pane.id).toImmutableSet())

        StudioNoticeUi.AttachmentFailed -> copy(
            attachmentErrorPanes = (attachmentErrorPanes - pane.id).toImmutableSet(),
        )

        StudioNoticeUi.HarnessFailed -> copy(
            harnessChoices = harnessChoices.copy(
                failedChats = pane.sessionId?.let { (harnessChoices.failedChats - it).toImmutableSet() }
                    ?: harnessChoices.failedChats,
            ),
        )

        StudioNoticeUi.DiagramDraft -> copy(
            diagramDraftReasons = pane.sessionId?.let { (diagramDraftReasons - it).toImmutableMap() }
                ?: diagramDraftReasons,
        )

        StudioNoticeUi.ProjectFailed, StudioNoticeUi.StopFailed, StudioNoticeUi.ConnectModel,
        StudioNoticeUi.ProjectModel, StudioNoticeUi.NativeChild, StudioNoticeUi.ReadOnly,
        StudioNoticeUi.StopUnsupported,
        -> if (isCurrent(target)) {
            copy(noticeDismissals = (noticeDismissals + target).toImmutableSet())
        } else {
            this
        }
    }
}

/** A new explicit attempt must reveal its own failure even if the machine's intermediate state is conflated. */
fun AiStudioScreenState.revealNotice(paneId: Int, notice: StudioNoticeUi): AiStudioScreenState = copy(
    noticeDismissals = noticeDismissals.filterNot { it.paneId == paneId && it.notice == notice }.toImmutableSet(),
)

/** Drops dismissals after navigation, source resolution or a changed model offer; runtime ticks preserve them. */
fun AiStudioScreenState.pruneNoticeDismissals(): AiStudioScreenState {
    val retained = noticeDismissals.filter { isCurrent(it) }.toImmutableSet()
    return if (retained == noticeDismissals) this else copy(noticeDismissals = retained)
}

private fun AiStudioScreenState.isCurrent(dismissal: StudioNoticeDismissalUi): Boolean {
    val pane = panes.firstOrNull { it.id == dismissal.paneId } ?: return false
    if (dismissal != noticeDismissal(pane, dismissal.notice)) return false
    return when (dismissal.notice) {
        StudioNoticeUi.ProjectFailed -> projectErrorPane == pane.id

        StudioNoticeUi.StopFailed -> pane.sessionId in stopFailures

        StudioNoticeUi.ReadOnly -> session(pane.sessionId)?.isContinuable == false

        StudioNoticeUi.StopUnsupported -> pane.sessionId in running && pane.sessionId in uncancellable

        StudioNoticeUi.NativeChild -> selectedNative(pane.sessionId) != PRIMARY_SUB_SESSION

        StudioNoticeUi.ConnectModel, StudioNoticeUi.ProjectModel -> true

        StudioNoticeUi.SubmitFailed, StudioNoticeUi.AttachmentFailed, StudioNoticeUi.HarnessFailed,
        StudioNoticeUi.DiagramDraft,
        -> false
    }
}

/** Captures the displayed context so a delayed close cannot dismiss a replacement pane's notice. */
fun AiStudioScreenState.noticeDismissal(pane: PaneUi, notice: StudioNoticeUi): StudioNoticeDismissalUi {
    val session = session(pane.sessionId)
    val isModelHint = notice == StudioNoticeUi.ConnectModel || notice == StudioNoticeUi.ProjectModel
    return StudioNoticeDismissalUi(
        paneId = pane.id,
        notice = notice,
        sessionId = pane.sessionId,
        projectId = pane.projectId ?: session?.projectId,
        subSession = subSessions[pane.sessionId] ?: PRIMARY_SUB_SESSION,
        modelId = if (isModelHint) {
            organisms[pane.sessionId]?.modelId ?: configurations[pane.sessionId]?.modelId
                ?: session?.modelId?.takeIf(String::isNotBlank) ?: settings.modelId
        } else {
            null
        },
        modelIds = if (isModelHint) models.map { it.id }.toImmutableSet() else persistentSetOf(),
        runStartedAt = if (notice == StudioNoticeUi.StopUnsupported) runStartedAt[pane.sessionId] else null,
    )
}
