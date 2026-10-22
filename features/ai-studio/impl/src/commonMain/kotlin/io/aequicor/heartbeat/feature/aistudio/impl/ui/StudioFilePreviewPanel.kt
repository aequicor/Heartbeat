package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbCodeEditor
import io.aequicor.heartbeat.ds.components.HbCopyButton
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbEmptyState
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbMarkdownBlockContent
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.parseHbMarkdown
import io.aequicor.heartbeat.ds.components.rememberHbLinkHandler
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioFilePreviewIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioFilePreviewModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioFilePreviewState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioFileView
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_binary
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_close
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_content
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_copy_failed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_copy_path
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_loading
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_path_copied
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_reload
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_show_formatted
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_show_source
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_too_large
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.file_preview_unavailable_hint
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** The side file preview bound to the window's preview store; watching it keeps the file fresh. */
@Composable
internal fun StudioFilePreviewPanel(model: StudioFilePreviewModel, modifier: Modifier = Modifier) {
    val state by produceState(StudioFilePreviewState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    StudioFilePreviewContent(state, model.store::intent, modifier)
}

/** Header with the file and its actions above the rendered Markdown, the read-only source or why it is not shown. */
@Composable
internal fun StudioFilePreviewContent(
    state: StudioFilePreviewState,
    onIntent: (StudioFilePreviewIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxSize().testTag("studio-file-preview"), gap = HbTheme.spacing.none) {
        PreviewHeader(state, onIntent)
        HbDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val content = state.content) {
                null -> HbLoadingState(stringResource(Res.string.file_preview_loading))

                is StudioFileView.Text -> if (state.isMarkdown && !state.isSource) {
                    MarkdownFile(content.text, onIntent)
                } else {
                    HbCodeEditor(
                        value = content.text,
                        onValueChange = {},
                        accessibleLabel = stringResource(Res.string.file_preview_content),
                        modifier = Modifier.fillMaxSize().padding(HbTheme.spacing.s),
                        language = previewLanguage(state.name),
                        isReadOnly = true,
                        revealLine = state.line,
                    )
                }

                StudioFileView.Binary -> Unshown(stringResource(Res.string.file_preview_binary), state)

                StudioFileView.TooLarge -> Unshown(stringResource(Res.string.file_preview_too_large), state)

                StudioFileView.Unavailable -> Unshown(
                    stringResource(Res.string.file_preview_unavailable),
                    state,
                    stringResource(Res.string.file_preview_unavailable_hint),
                )
            }
        }
    }
}

@Composable
private fun PreviewHeader(state: StudioFilePreviewState, onIntent: (StudioFilePreviewIntent) -> Unit) {
    val shownPath = state.path ?: state.requested
    HbRow(
        Modifier.fillMaxWidth().height(HbTheme.dimensions.headerHeight).padding(horizontal = HbTheme.spacing.m),
        gap = HbTheme.spacing.s,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HbIcon(if (state.isMarkdown) HbIcons.FileText else HbIcons.FileCode, contentDescription = null)
        HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.none) {
            HbText(
                state.name,
                Modifier.semantics { heading() },
                style = HbTheme.typography.label,
                maxLines = 1,
            )
            HbText(shownPath, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary, maxLines = 1)
        }
        if (state.isMarkdown && state.content is StudioFileView.Text) {
            val description = stringResource(
                if (state.isSource) Res.string.file_preview_show_formatted else Res.string.file_preview_show_source,
            )
            HbIconButton(
                HbIcons.FileCode,
                description,
                { onIntent(StudioFilePreviewIntent.ToggleSource) },
                isSelected = state.isSource,
            )
        }
        HbIconButton(
            HbIcons.Refresh,
            stringResource(Res.string.file_preview_reload),
            { onIntent(StudioFilePreviewIntent.Reload) },
            enabled = state.path != null,
        )
        HbCopyButton(
            text = shownPath,
            contentDescription = stringResource(Res.string.file_preview_copy_path),
            copiedDescription = stringResource(Res.string.file_preview_path_copied),
            failedDescription = stringResource(Res.string.file_preview_copy_failed),
        )
        HbIconButton(
            HbIcons.Close,
            stringResource(Res.string.file_preview_close),
            { onIntent(StudioFilePreviewIntent.Close) },
        )
    }
}

/** Rendered Markdown; its file links open beside the shown file, web links through the platform. */
@Composable
private fun MarkdownFile(text: String, onIntent: (StudioFilePreviewIntent) -> Unit) {
    val blocks = remember(text) { parseHbMarkdown(text) }
    val onLinkClick = rememberHbLinkHandler { file ->
        onIntent(StudioFilePreviewIntent.OpenLinked(file.path, file.line))
    }
    HbLazyColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.s) {
        items(blocks, key = { it.id }, contentType = { it.kind }) { block ->
            // Each block owns its selection so lazily removed items never leave a shared registrar.
            SelectionContainer { HbMarkdownBlockContent(block, onLinkClick = onLinkClick) }
        }
    }
}

@Composable
private fun Unshown(title: String, state: StudioFilePreviewState, hint: String? = null) {
    HbEmptyState(
        title = title,
        description = listOfNotNull(hint, state.path ?: state.requested).joinToString("\n"),
    )
}

/** Fence language of a file name for syntax highlighting; unknown extensions show plain text. */
internal fun previewLanguage(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "jsx", "mjs", "cjs", "ts", "tsx" -> "javascript"
    "json" -> "json"
    "py" -> "python"
    "sh", "bash", "zsh" -> "shell"
    else -> null
}
