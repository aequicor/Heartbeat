package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessagePart
import io.aequicor.heartbeat.ds.components.HbToolAction
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolCall
import io.aequicor.heartbeat.ds.components.HbToolKind
import io.aequicor.heartbeat.ds.components.HbToolStatus
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildPhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.BuildUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeActionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeJournalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreePhaseUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.WorktreeUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.author_studio
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_action_working
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_cancel
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_position
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_queued
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_build_waiting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_create_pr
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_action_delivery
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_action_unverified
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_branch_changed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_branch_moved
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_branch_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_checkout_dirty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_checkout_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_merge_in_progress
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_native_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_pr_base
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_failure_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_journal_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_journal_loading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_journal_retry
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_leave
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_merge
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_merge_target
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_mode
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_open_pr
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_preparing
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_recheck
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_recovery
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_refine
import io.aequicor.heartbeat.feature.aistudio.impl.resources.worktree_result
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

/**
 * Localized copy of the worktree cards of a transcript. Templates keep their `%1$s` / `%1$d` placeholders for
 * [fill], so the transcript tail can be prepared outside composition.
 */
@Immutable
internal data class WorktreeLabels(
    val author: String,
    val worktree: String,
    val preparing: String,
    val journalLoading: String,
    val journalFailed: String,
    val journalRetry: String,
    val result: String,
    val createPr: String,
    val mergeTemplate: String,
    val mergeTargetTemplate: String,
    val refine: String,
    val leave: String,
    val openPr: String,
    val actionWorking: String,
    val failed: String,
    val recovery: String,
    val recheck: String,
    val builds: BuildLabels,
    val failures: WorktreeFailureLabels,
)

/** Build copy beyond the shared tool status: queue details and the cancel action. */
@Immutable
internal data class BuildLabels(
    val cancel: String,
    val queued: String,
    val waiting: String,
    val unknown: String,
    val positionTemplate: String,
)

/** Explanations of task failure codes; an unrecognized code never exposes its raw diagnostic. */
@Immutable
internal data class WorktreeFailureLabels(
    val checkoutDirty: String,
    val branchChanged: String,
    val branchMoved: String,
    val branchUnavailable: String,
    val checkoutUnavailable: String,
    val pullRequestBase: String,
    val nativeUnknown: String,
    val actionDelivery: String,
    val mergeInProgress: String,
    val actionUnverified: String,
    val unknown: String,
) {
    fun of(code: String): String = when (code) {
        "OriginalCheckoutDirty" -> checkoutDirty
        "OriginalBranchChanged" -> branchChanged
        "OriginalBranchMoved" -> branchMoved
        "OriginalBranchUnavailable" -> branchUnavailable
        "WorktreeUnavailable", "ProjectUnavailable" -> checkoutUnavailable
        "PullRequestBaseUnavailable" -> pullRequestBase
        "NativeOutcomeUnknown", "InterruptedBeforeAcceptance" -> nativeUnknown
        "ActionDeliveryFailed" -> actionDelivery
        "MergeInProgress", "MergeLeaseReleasePending" -> mergeInProgress
        "ActionResultUnverified", "ActionUnverified" -> actionUnverified
        else -> unknown
    }
}

/** What a worktree card action does: one of the store intents, or opening the verified pull request. */
internal sealed interface WorktreeCommand {
    /** Sends [intent] to the screen store. */
    data class Send(val intent: AiStudioScreenIntent) : WorktreeCommand

    /** Opens the verified pull request [url] in the browser. */
    data class OpenLink(val url: String) : WorktreeCommand
}

/**
 * Worktree state of one pane. [key] is the session id, or the pane on a new-session page while its worktree
 * session is prepared or its failed journal awaits a retry; [task] belongs to the open session [sessionId].
 */
@Immutable
internal data class WorktreeFeed(
    val key: String,
    val sessionId: String?,
    val task: WorktreeUi?,
    val journal: WorktreeJournalUi,
    val isPreparing: Boolean,
)

/**
 * Host-owned cards of one pane, woven into the transcript at their invocation points, with the command behind
 * each card action.
 */
@Immutable
internal data class WorktreeTimeline(
    val messages: ImmutableList<HbChatMessage> = persistentListOf(),
    /** Cards that stay woven after they leave [messages]: invoked builds are history events, not current state. */
    val retained: ImmutableSet<String> = persistentSetOf(),
    val commands: ImmutableMap<String, WorktreeCommand> = persistentMapOf(),
) {
    /** Runs the command of action [actionId] pressed on card [callId]; other tools have no host command. */
    fun dispatch(
        callId: String,
        actionId: String,
        onIntent: (AiStudioScreenIntent) -> Unit,
        openLink: (String) -> Unit,
    ) {
        when (val command = commands[commandKey(callId, actionId)]) {
            is WorktreeCommand.Send -> onIntent(command.intent)
            is WorktreeCommand.OpenLink -> openLink(command.url)
            null -> log.w { "No host command for tool=$callId action=$actionId" }
        }
    }
}

/**
 * Opens the verified pull request. A missing or failing browser must not crash the transcript, and platforms
 * report it differently: Android rejects the intent, while desktop AWT throws I/O, unsupported-action, URI
 * syntax or security failures. Every one is logged and the card stays usable.
 */
internal fun openPullRequest(links: UriHandler, url: String) {
    log.i { "Open worktree pull request" }
    try {
        links.openUri(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Pull request could not be opened" }
    }
}

/**
 * The worktree of a pane. A new-session page gets a feed only while it prepares a worktree session or while a
 * failed journal must be retried before a worktree task can start; a transient journal restoration stays quiet.
 */
internal fun PaneContent.worktreeFeed(): WorktreeFeed? {
    val isPreparing = pane.isCreating && pane.isWorktree
    val hasContext = session?.isWorktree == true || pane.isWorktree || worktree != null
    val isJournalBlocking = pane.isWorktree && worktreeJournal == WorktreeJournalUi.Error
    val key = pane.sessionId ?: "pane-${pane.id}".takeIf { isPreparing || isJournalBlocking }
    if (!hasContext || key == null) return null
    return WorktreeFeed(key, session?.id, worktree.takeIf { session != null }, worktreeJournal, isPreparing)
}

/**
 * Cards of [feed] for the transcript: the journal notice, one card for the task (`worktree:<key>`) and one per
 * recent build (`build:<id>`). Stable ids keep disclosure state while the transcript streams; the recent-build
 * window offers the last [MAX_BUILDS] and refreshes [shownCards], while every invoked build stays retained.
 * Until the journal is restored the task card hides its phase and decisions, which may be stale.
 */
internal fun worktreeTimeline(
    feed: WorktreeFeed?,
    labels: WorktreeLabels,
    shownCards: Set<String> = emptySet(),
): WorktreeTimeline {
    if (feed == null) return WorktreeTimeline()
    val builds = feed.buildCards(labels.builds, shownCards)
    val cards = listOfNotNull(feed.journalCard(labels), feed.taskCard(labels)) + builds
    if (cards.isEmpty()) return WorktreeTimeline()
    return WorktreeTimeline(
        cards.map { it.message(labels.author) }.toImmutableList(),
        feed.buildIds(),
        cards.flatMap { card -> card.commands.map { commandKey(card.call.id, it.action.id) to it.command } }
            .toMap().toImmutableMap(),
    )
}

/** Worktree copy in the current language. */
@Composable
internal fun worktreeLabels(): WorktreeLabels = WorktreeLabels(
    author = stringResource(Res.string.author_studio),
    worktree = stringResource(Res.string.worktree_mode),
    preparing = stringResource(Res.string.worktree_preparing),
    journalLoading = stringResource(Res.string.worktree_journal_loading),
    journalFailed = stringResource(Res.string.worktree_journal_failed),
    journalRetry = stringResource(Res.string.worktree_journal_retry),
    result = stringResource(Res.string.worktree_result),
    createPr = stringResource(Res.string.worktree_create_pr),
    mergeTemplate = stringResource(Res.string.worktree_merge),
    mergeTargetTemplate = stringResource(Res.string.worktree_merge_target),
    refine = stringResource(Res.string.worktree_refine),
    leave = stringResource(Res.string.worktree_leave),
    openPr = stringResource(Res.string.worktree_open_pr),
    actionWorking = stringResource(Res.string.worktree_action_working),
    failed = stringResource(Res.string.worktree_failed),
    recovery = stringResource(Res.string.worktree_recovery),
    recheck = stringResource(Res.string.worktree_recheck),
    builds = BuildLabels(
        cancel = stringResource(Res.string.worktree_build_cancel),
        queued = stringResource(Res.string.worktree_build_queued),
        waiting = stringResource(Res.string.worktree_build_waiting),
        unknown = stringResource(Res.string.worktree_build_unknown),
        positionTemplate = stringResource(Res.string.worktree_build_position),
    ),
    failures = WorktreeFailureLabels(
        checkoutDirty = stringResource(Res.string.worktree_failure_checkout_dirty),
        branchChanged = stringResource(Res.string.worktree_failure_branch_changed),
        branchMoved = stringResource(Res.string.worktree_failure_branch_moved),
        branchUnavailable = stringResource(Res.string.worktree_failure_branch_unavailable),
        checkoutUnavailable = stringResource(Res.string.worktree_failure_checkout_unavailable),
        pullRequestBase = stringResource(Res.string.worktree_failure_pr_base),
        nativeUnknown = stringResource(Res.string.worktree_failure_native_unknown),
        actionDelivery = stringResource(Res.string.worktree_failure_action_delivery),
        mergeInProgress = stringResource(Res.string.worktree_failure_merge_in_progress),
        actionUnverified = stringResource(Res.string.worktree_failure_action_unverified),
        unknown = stringResource(Res.string.worktree_failure_unknown),
    ),
)

/** A card action and what it does. */
private data class CardCommand(val action: HbToolAction, val command: WorktreeCommand)

private class WorktreeCard(val call: HbToolCall, val commands: List<CardCommand>) {
    fun message(author: String): HbChatMessage = HbChatMessage(
        id = call.id,
        author = author,
        text = "",
        role = HbChatRole.System,
        kind = HbMessageKind.Tool,
        appearance = CardAppearance,
        parts = persistentListOf(HbMessagePart.Tool(call)),
    )
}

private fun worktreeCard(
    id: String,
    title: String,
    status: HbToolStatus,
    summary: String = "",
    blocks: List<HbToolBlock> = emptyList(),
    commands: List<CardCommand> = emptyList(),
): WorktreeCard = WorktreeCard(
    HbToolCall(
        id = id,
        title = title,
        status = status,
        summary = summary,
        blocks = blocks.toImmutableList(),
        kind = HbToolKind.Worktree,
        actions = commands.map { it.action }.toImmutableList(),
    ),
    commands,
)

private fun WorktreeFeed.journalCard(labels: WorktreeLabels): WorktreeCard? = when (journal) {
    WorktreeJournalUi.Loading -> worktreeCard(
        JOURNAL_LOADING_ID,
        labels.worktree,
        HbToolStatus.Running,
        summary = labels.journalLoading,
    )

    WorktreeJournalUi.Error -> worktreeCard(
        JOURNAL_ERROR_ID,
        labels.worktree,
        HbToolStatus.Error,
        summary = labels.journalFailed,
        commands = listOf(
            CardCommand(
                HbToolAction(JOURNAL_RETRY_ID, labels.journalRetry),
                WorktreeCommand.Send(AiStudioScreenIntent.RetryWorktreeJournal),
            ),
        ),
    )

    WorktreeJournalUi.Ready -> null
}

private fun WorktreeFeed.taskCard(labels: WorktreeLabels): WorktreeCard? {
    val id = "worktree:$key"
    if (isPreparing) return worktreeCard(id, labels.preparing, HbToolStatus.Running)
    val task = task ?: return null
    val session = sessionId ?: return null
    val openPr = task.pullRequestUrl?.let {
        CardCommand(HbToolAction(OPEN_PR_ID, labels.openPr, HbButtonStyle.Ghost), WorktreeCommand.OpenLink(it))
    }
    // Until the journal is restored the phase is unknown: the card only links the verified pull request.
    val isRestored = journal == WorktreeJournalUi.Ready
    val linked = openPr?.let {
        val status = if (isRestored) HbToolStatus.Complete else HbToolStatus.Pending
        worktreeCard(id, labels.worktree, status, task.branch.orEmpty(), commands = listOf(it))
    }
    if (!isRestored) return linked
    return task.phaseCard(id, session, labels, openPr) ?: linked
}

/**
 * The card of a restored phase; an idle or retained checkout only links its pull request. A verified pull request
 * stays linked in every phase, e.g. while builds started by the action still run.
 */
private fun WorktreeUi.phaseCard(
    id: String,
    session: String,
    labels: WorktreeLabels,
    openPr: CardCommand?,
): WorktreeCard? {
    val branch = branch.orEmpty()
    val link = listOfNotNull(openPr)
    return when (phase) {
        WorktreePhaseUi.Preparing -> worktreeCard(id, labels.preparing, HbToolStatus.Running, branch, commands = link)

        WorktreePhaseUi.Working, WorktreePhaseUi.CompletionSignaled ->
            worktreeCard(id, labels.worktree, HbToolStatus.Running, branch, commands = link)

        WorktreePhaseUi.ActionWorking ->
            worktreeCard(id, labels.actionWorking, HbToolStatus.Running, branch, commands = link)

        // The agent's summary stays in view: the decision depends on what was done and where it merges.
        WorktreePhaseUi.AwaitingDecision -> worktreeCard(
            id,
            labels.result,
            HbToolStatus.Complete,
            summary = listOfNotNull(
                summary?.trim()?.takeIf { it.isNotEmpty() },
                sourceBranch?.let { fill(labels.mergeTargetTemplate, it) },
            ).ifEmpty { listOf(branch) }.joinToString("\n"),
            commands = decisions(session, this, labels) + link,
        )

        WorktreePhaseUi.RecoveryRequired, WorktreePhaseUi.Failed -> worktreeCard(
            id,
            if (phase == WorktreePhaseUi.Failed) labels.failed else labels.recovery,
            HbToolStatus.Error,
            summary = failure?.let(labels.failures::of).orEmpty(),
            commands = listOfNotNull(
                CardCommand(
                    HbToolAction(RECHECK_ID, labels.recheck),
                    WorktreeCommand.Send(AiStudioScreenIntent.RecheckWorktree(session)),
                ),
                openPr,
            ),
        )

        WorktreePhaseUi.Idle, WorktreePhaseUi.Retained -> null
    }
}

/** Explicit result decisions; local merge needs the branch the task started from. */
private fun decisions(session: String, task: WorktreeUi, labels: WorktreeLabels): List<CardCommand> =
    WorktreeActionUi.entries.mapNotNull { action ->
        val label = when (action) {
            WorktreeActionUi.CreatePr -> labels.createPr
            WorktreeActionUi.Merge -> task.sourceBranch?.let { fill(labels.mergeTemplate, it) }
            WorktreeActionUi.Refine -> labels.refine
            WorktreeActionUi.Leave -> labels.leave
        } ?: return@mapNotNull null
        val style = when (action) {
            WorktreeActionUi.CreatePr -> HbButtonStyle.Primary
            WorktreeActionUi.Leave -> HbButtonStyle.Ghost
            WorktreeActionUi.Merge, WorktreeActionUi.Refine -> HbButtonStyle.Secondary
        }
        CardCommand(
            HbToolAction("worktree-action-${action.name}", label, style),
            WorktreeCommand.Send(AiStudioScreenIntent.DecideWorktree(session, action)),
        )
    }

private fun WorktreeFeed.buildCards(labels: BuildLabels, shownCards: Set<String>): List<WorktreeCard> {
    val task = task ?: return emptyList()
    val session = sessionId ?: return emptyList()
    val isOperational = journal == WorktreeJournalUi.Ready
    val recentStart = task.builds.size - MAX_BUILDS
    return task.builds.filterIndexed { index, build ->
        index >= recentStart || "build:${build.id}" in shownCards
    }.map { build ->
        val output = build.outputTail().takeIf { build.phase in DiagnosedPhases }.orEmpty()
        worktreeCard(
            id = "build:${build.id}",
            title = build.command,
            status = build.phase.toolStatus(),
            summary = listOfNotNull(
                build.phase.detail(labels),
                build.queuePosition?.let { fill(labels.positionTemplate, it) },
                build.failure,
            ).joinToString(" · "),
            blocks = listOfNotNull(
                output.takeIf { it.isNotBlank() }?.let { HbToolBlock.Console("build-output-${build.id}", it) },
            ),
            commands = listOfNotNull(
                CardCommand(
                    HbToolAction("cancel-build-${build.id}", labels.cancel),
                    WorktreeCommand.Send(AiStudioScreenIntent.CancelWorktreeBuild(session, build.id)),
                ).takeIf { isOperational && build.phase in CancellablePhases },
            ),
        )
    }
}

/** Every build of the task keeps its card woven, even when it leaves the recent-build window. */
private fun WorktreeFeed.buildIds(): ImmutableSet<String> =
    task?.builds?.mapTo(HashSet()) { "build:${it.id}" }?.toPersistentSet() ?: persistentSetOf()

/** The last lines of the bounded journal output; the end of a log carries the diagnostic. */
private fun BuildUi.outputTail(): String =
    output.takeLast(BUILD_OUTPUT_CHARS).lines().takeLast(BUILD_OUTPUT_LINES).joinToString("\n")

private fun BuildPhaseUi.toolStatus(): HbToolStatus = when (this) {
    BuildPhaseUi.Queued, BuildPhaseUi.WaitingForResource -> HbToolStatus.Pending
    BuildPhaseUi.Running -> HbToolStatus.Running
    BuildPhaseUi.Completed -> HbToolStatus.Complete
    BuildPhaseUi.Failed, BuildPhaseUi.Unknown -> HbToolStatus.Error
    BuildPhaseUi.Cancelled -> HbToolStatus.Cancelled
}

/** Queue details the shared tool status cannot tell. */
private fun BuildPhaseUi.detail(labels: BuildLabels): String? = when (this) {
    BuildPhaseUi.Queued -> labels.queued
    BuildPhaseUi.WaitingForResource -> labels.waiting
    BuildPhaseUi.Unknown -> labels.unknown
    BuildPhaseUi.Running, BuildPhaseUi.Completed, BuildPhaseUi.Cancelled, BuildPhaseUi.Failed -> null
}

private val CancellablePhases = setOf(BuildPhaseUi.Queued, BuildPhaseUi.WaitingForResource, BuildPhaseUi.Running)

/** Phases whose log tail explains the outcome. */
private val DiagnosedPhases = setOf(BuildPhaseUi.Failed, BuildPhaseUi.Unknown)

private fun commandKey(callId: String, actionId: String): String = "${callId.length}:$callId:$actionId"

private val log = Log.tag("StudioWorktreeFeed")

/** Host entries share the answer column; the System role drops the answer surface and copy footer. */
private val CardAppearance = HbMessageAppearance(widthFraction = 1f, isUnified = true)

internal const val JOURNAL_LOADING_ID = "worktree-journal-loading"
internal const val JOURNAL_ERROR_ID = "worktree-journal-error"
internal const val JOURNAL_RETRY_ID = "worktree-journal-retry"
internal const val RECHECK_ID = "worktree-recheck"
internal const val OPEN_PR_ID = "worktree-open-pr"
private const val MAX_BUILDS = 3
private const val BUILD_OUTPUT_CHARS = 4000
private const val BUILD_OUTPUT_LINES = 40
