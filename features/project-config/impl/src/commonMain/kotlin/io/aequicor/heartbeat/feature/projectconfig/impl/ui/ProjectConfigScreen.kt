package io.aequicor.heartbeat.feature.projectconfig.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.BoundUnitUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ContentKindUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ContentUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ImportUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.NoticeUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.PhaseUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigModel
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenIntent
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectConfigScreenState
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProjectUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProposalKindUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProposalUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ProposalVersionUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.ReviewUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.SectionUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.SourceUi
import io.aequicor.heartbeat.feature.projectconfig.impl.presentation.TaskFailureUi
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.Res
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_back
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_load_failed
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_loading
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_busy
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_empty
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_exported
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_format
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_imported
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_limit
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_name
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_ok
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_opened
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_saved
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_storage
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_unavailable
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_unreadable
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_unwritable
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_version
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_notice_write_back
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_off
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_retry
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_title
import io.aequicor.heartbeat.feature.projectconfig.impl.resources.pc_unsupported
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Project configuration: changes to trust, projects with their folders and the transfer of `.hbai` files. */
@Composable
internal fun ProjectConfigScreen(model: ProjectConfigModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(ProjectConfigScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ProjectConfigContent(state, model.store::intent, onBack, modifier)
}

/** Stateless configuration screen; the risk confirmation and the entity chooser open above the list. */
@Composable
internal fun ProjectConfigContent(
    state: ProjectConfigScreenState,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("project-config"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.pc_title),
                leadingInset = if (HbTheme.dimensions.isDesktop) {
                    HbTheme.spacing.m
                } else {
                    HbTheme.dimensions.titlebarLeadingInset
                },
                navigation = {
                    HbIconButton(
                        HbIcons.ArrowLeft,
                        stringResource(Res.string.pc_back),
                        onBack,
                        Modifier.testTag("project-config-back"),
                    )
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth)
                    .fillMaxSize()
                    .testTag("project-config-list"),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                screenItems(state, onIntent)
            }
        }
    }
    state.confirmed?.let { RiskDialog(it, onIntent) }
    state.picker?.let { EntityPicker(it, state.entities, onIntent) }
    state.removing?.let { RemovalDialog(it, onIntent) }
    val pending = state.importing
    val existing = pending?.existing
    if (pending != null && existing != null) ImportDialog(pending, existing, onIntent)
}

private fun LazyListScope.screenItems(state: ProjectConfigScreenState, onIntent: (ProjectConfigScreenIntent) -> Unit) {
    state.notice?.let { notice -> item(key = "notice") { NoticeBanner(notice, onIntent) } }
    when (state.phase) {
        PhaseUi.Unsupported -> item(key = "phase") { HbEmptyState(stringResource(Res.string.pc_unsupported)) }

        PhaseUi.Off -> item(key = "phase") { HbEmptyState(stringResource(Res.string.pc_off)) }

        PhaseUi.Loading -> item(key = "phase") { HbLoadingState(stringResource(Res.string.pc_loading)) }

        PhaseUi.Failed -> item(key = "phase") {
            HbBanner(stringResource(Res.string.pc_load_failed), Modifier.testTag("project-config-failed")) {
                HbButton(
                    stringResource(Res.string.pc_retry),
                    { onIntent(ProjectConfigScreenIntent.Reload) },
                    style = HbButtonStyle.Secondary,
                    size = HbButtonSize.Small,
                )
            }
        }

        PhaseUi.Ready -> {
            item(key = "storage") { StorageSection(state.defaultStorage, state.isStorageBusy, onIntent) }
            item(key = "proposals") { ProposalsSection(state.proposals, onIntent) }
            item(key = "projects") { ProjectsSection(state.projects, state.isBusy, state.isStorageBusy, onIntent) }
            item(key = "transfer") { TransferSection(state.bundle, state.isBusy, onIntent) }
        }
    }
}

@Composable
private fun NoticeBanner(
    notice: NoticeUi,
    onIntent: (ProjectConfigScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (notice) {
        is NoticeUi.Saved -> stringResource(Res.string.pc_notice_saved, notice.units, notice.skipped)
        is NoticeUi.Exported -> stringResource(Res.string.pc_notice_exported, notice.units)
        is NoticeUi.Opened -> stringResource(Res.string.pc_notice_opened, notice.proposals)
        is NoticeUi.Imported -> stringResource(Res.string.pc_notice_imported, notice.units, notice.skipped)
        is NoticeUi.Failed -> stringResource(notice.reason.message())
        NoticeUi.StorageFailed -> stringResource(Res.string.pc_notice_storage)
        NoticeUi.WriteBackFailed -> stringResource(Res.string.pc_notice_write_back)
    }
    val tone = when {
        notice is NoticeUi.Failed || notice == NoticeUi.StorageFailed || notice == NoticeUi.WriteBackFailed ->
            HbTone.Danger

        notice is NoticeUi.Saved && notice.skipped > 0 -> HbTone.Warning

        else -> HbTone.Success
    }
    HbBanner(message, modifier.testTag("project-config-notice"), tone = tone) {
        HbButton(
            stringResource(Res.string.pc_notice_ok),
            { onIntent(ProjectConfigScreenIntent.DismissNotice) },
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

private fun TaskFailureUi.message() = when (this) {
    TaskFailureUi.Busy -> Res.string.pc_notice_busy
    TaskFailureUi.Unavailable -> Res.string.pc_notice_unavailable
    TaskFailureUi.Unreadable -> Res.string.pc_notice_unreadable
    TaskFailureUi.Format -> Res.string.pc_notice_format
    TaskFailureUi.Version -> Res.string.pc_notice_version
    TaskFailureUi.Limit -> Res.string.pc_notice_limit
    TaskFailureUi.Unwritable -> Res.string.pc_notice_unwritable
    TaskFailureUi.Empty -> Res.string.pc_notice_empty
    TaskFailureUi.Name -> Res.string.pc_notice_name
}

private val previewState = ProjectConfigScreenState(
    phase = PhaseUi.Ready,
    proposals = persistentListOf(
        ProposalUi(
            id = "p",
            digest = "d",
            title = "Code review",
            unit = "review",
            section = SectionUi.Harness,
            source = SourceUi("heartbeat", isBundle = false),
            kind = ProposalKindUi.Create,
            contents = persistentListOf(
                ContentUi(ContentKindUi.Skill, "tests"),
                ContentUi(ContentKindUi.Script, "verify"),
            ),
            hasCode = true,
            review = ReviewUi.Risky("Скрипт запускает внешние команды без ограничений", isUncertain = false),
            problems = persistentListOf(),
            failure = null,
            isApplying = false,
            isKeepAppOffered = false,
        ),
    ),
    projects = persistentListOf(
        ProjectUi(
            key = "p",
            name = "heartbeat",
            hasFolder = true,
            isSyncing = false,
            isUnreadable = false,
            units = persistentListOf(BoundUnitUi(SectionUi.Learning, "learning", "kodirovka-konsoli")),
        ),
    ),
    bundle = "команда.hbai",
    notice = NoticeUi.Saved(units = 2, skipped = 1),
)

@Preview
@Composable
private fun ProjectConfigLightPreview() {
    HbTheme(darkTheme = false) { ProjectConfigContent(previewState, {}, null) }
}

@Preview
@Composable
private fun ProjectConfigDarkPreview() {
    HbTheme(darkTheme = true) { ProjectConfigContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun ProjectConfigRiskPreview() {
    val state = previewState.copy(confirming = ProposalVersionUi("p", "d"))
    HbTheme(darkTheme = false) { ProjectConfigContent(state, {}, null) }
}

@Preview
@Composable
private fun ProjectConfigImportPreview() {
    val existing = persistentListOf("harnesses/review", "learning/code-style")
    val state = previewState.copy(importing = ImportUi("r", "team.hbai", "p", "Heartbeat", existing))
    HbTheme(darkTheme = true) { ProjectConfigContent(state, {}, null) }
}

@Preview
@Composable
private fun ProjectConfigFailedPreview() {
    HbTheme(darkTheme = true) {
        ProjectConfigContent(
            ProjectConfigScreenState(phase = PhaseUi.Failed, notice = NoticeUi.Failed(TaskFailureUi.Version)),
            {},
            null,
        )
    }
}
