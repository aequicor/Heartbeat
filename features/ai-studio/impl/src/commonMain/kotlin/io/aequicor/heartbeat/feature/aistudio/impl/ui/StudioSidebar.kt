package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbNavigationHeader
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectGroupUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarContent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SidebarMode
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.sidebarContent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.rail_archive
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_archive_empty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_collapsed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_expanded
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_new_in_project
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_new_session
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_no_results
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_pinned
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_project_empty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_projects
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_recent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_results
import io.aequicor.heartbeat.feature.aistudio.impl.resources.sidebar_search
import io.aequicor.heartbeat.feature.aistudio.impl.resources.studio_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Session lists of the studio. Menus are transient composition state; everything else comes from the store. */
@Composable
internal fun StudioSidebar(
    input: SidebarInput,
    onIntent: (AiStudioScreenIntent) -> Unit,
    canOpenBeside: Boolean,
    modifier: Modifier = Modifier,
) {
    val sidebar = input.sidebar
    val content = remember(input.projects, input.sessions, input.running, sidebar) {
        sidebarContent(input.projects, input.sessions, input.running, sidebar)
    }
    var openMenu by remember { mutableStateOf<String?>(null) }
    val rows = SessionRows(
        selectedId = input.selectedId,
        renaming = sidebar.renaming,
        openMenu = openMenu,
        onMenu = { openMenu = it },
        canOpenBeside = canOpenBeside,
        onIntent = onIntent,
    )
    HbColumn(modifier.testTag("studio-sidebar"), gap = HbTheme.spacing.none) {
        HbText(
            text = stringResource(
                if (sidebar.mode == SidebarMode.Archive) Res.string.rail_archive else Res.string.studio_title,
            ),
            modifier = Modifier.padding(
                start = HbTheme.spacing.l,
                end = HbTheme.spacing.l,
                top = HbTheme.spacing.l,
                bottom = HbTheme.spacing.s,
            ),
            style = HbTheme.typography.title,
            maxLines = 1,
        )
        if (sidebar.isSearchVisible) SearchField(sidebar.query, onIntent)
        HbLazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            gap = HbTheme.spacing.xxs,
            contentPadding = PaddingValues(horizontal = HbTheme.spacing.s, vertical = HbTheme.spacing.xs),
        ) {
            val results = content.results
            when {
                results != null -> searchResults(results, rows)
                sidebar.mode == SidebarMode.Archive -> archivedSessions(content, rows)
                else -> workspaceSessions(content, input, rows, onIntent)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onIntent: (AiStudioScreenIntent) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    HbTextField(
        value = query,
        onValueChange = { onIntent(AiStudioScreenIntent.SearchChanged(it)) },
        modifier = modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.xs)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val isEscape = event.key == Key.Escape && event.type == KeyEventType.KeyUp
                if (isEscape) onIntent(AiStudioScreenIntent.ToggleSearch)
                isEscape
            }
            .testTag("sidebar-search"),
        placeholder = stringResource(Res.string.sidebar_search),
    )
}

private fun LazyListScope.searchResults(results: List<SessionUi>, rows: SessionRows) {
    item(key = "results-header") { HbNavigationHeader(stringResource(Res.string.sidebar_results)) }
    if (results.isEmpty()) {
        item(key = "results-empty") { EmptyNote(stringResource(Res.string.sidebar_no_results)) }
    }
    items(results, key = { "result-${it.id}" }) { rows.SessionRow(it, "result") }
}

private fun LazyListScope.archivedSessions(content: SidebarContent, rows: SessionRows) {
    if (content.archived.isEmpty()) {
        item(key = "archive-empty") { EmptyNote(stringResource(Res.string.sidebar_archive_empty)) }
    }
    items(content.archived, key = { "archived-${it.id}" }) { rows.SessionRow(it, "archived") }
}

private fun LazyListScope.workspaceSessions(
    content: SidebarContent,
    input: SidebarInput,
    rows: SessionRows,
    onIntent: (AiStudioScreenIntent) -> Unit,
) {
    item(key = "new-session") {
        HbNavigationItem(
            label = stringResource(Res.string.sidebar_new_session),
            onClick = { onIntent(AiStudioScreenIntent.NewSession(input.newSessionProjectId)) },
            modifier = Modifier.testTag("sidebar-new-session"),
            icon = HbIcons.Edit,
        )
    }
    if (content.pinned.isNotEmpty()) {
        item(key = "pinned-header") { HbNavigationHeader(stringResource(Res.string.sidebar_pinned)) }
        items(content.pinned, key = { "pinned-${it.id}" }) { rows.SessionRow(it, "pinned") }
    }
    item(key = "projects-header") {
        SectionHeader(
            title = Res.string.sidebar_projects,
            isExpanded = input.sidebar.isProjectsExpanded,
            onToggle = { onIntent(AiStudioScreenIntent.ToggleProjectsSection) },
        )
    }
    if (input.sidebar.isProjectsExpanded) {
        content.projects.forEach { group -> projectGroup(group, rows, onIntent) }
    }
    item(key = "recent-header") {
        SectionHeader(
            title = Res.string.sidebar_recent,
            isExpanded = input.sidebar.isRecentExpanded,
            onToggle = { onIntent(AiStudioScreenIntent.ToggleRecentSection) },
        )
    }
    if (input.sidebar.isRecentExpanded) {
        items(content.recent, key = { "recent-${it.id}" }) { rows.SessionRow(it, "recent") }
    }
}

private fun LazyListScope.projectGroup(
    group: ProjectGroupUi,
    rows: SessionRows,
    onIntent: (AiStudioScreenIntent) -> Unit,
) {
    item(key = "project-${group.project.id}") {
        HbNavigationItem(
            label = group.project.name,
            onClick = { onIntent(AiStudioScreenIntent.ToggleProject(group.project.id)) },
            modifier = Modifier.testTag("project-${group.project.id}"),
            icon = if (group.isExpanded) HbIcons.FolderOpen else HbIcons.Folder,
        ) { isActive ->
            if (isActive) {
                HbIconButton(
                    icon = HbIcons.Plus,
                    contentDescription = stringResource(Res.string.sidebar_new_in_project, group.project.name),
                    onClick = { onIntent(AiStudioScreenIntent.NewSession(group.project.id)) },
                )
            }
        }
    }
    if (group.isExpanded) {
        if (group.sessions.isEmpty()) {
            item(key = "project-${group.project.id}-empty") {
                EmptyNote(
                    stringResource(Res.string.sidebar_project_empty),
                    Modifier.padding(start = HbTheme.spacing.xl),
                )
            }
        }
        items(
            group.sessions,
            key = { "project-${group.project.id}-${it.id}" },
        ) { rows.SessionRow(it, "project", level = 1) }
    }
}

@Composable
private fun SectionHeader(
    title: StringResource,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HbNavigationHeader(
        title = stringResource(title),
        modifier = modifier,
        isExpanded = isExpanded,
        expandedLabel = stringResource(Res.string.sidebar_expanded),
        collapsedLabel = stringResource(Res.string.sidebar_collapsed),
        onToggle = onToggle,
    )
}

@Composable
private fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    HbText(
        text = text,
        modifier = modifier.padding(horizontal = HbTheme.spacing.m, vertical = HbTheme.spacing.s),
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
    )
}
