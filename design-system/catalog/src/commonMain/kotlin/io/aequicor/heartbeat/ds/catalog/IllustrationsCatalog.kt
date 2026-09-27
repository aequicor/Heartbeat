package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbIllustration
import io.aequicor.heartbeat.ds.components.HbIllustrationKind
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun IllustrationsCatalog(modifier: Modifier = Modifier) {
    HbLazyColumn(modifier = modifier.testTag("illustrations-catalog")) {
        item { CatalogHeading(HbString.Illustrations, HbString.IllustrationsDescription) }
        item {
            HbFlowRow(gap = HbTheme.spacing.l) {
                HbIllustrationKind.entries.forEach { illustration ->
                    IllustrationTile(illustration)
                }
            }
        }
    }
}

@Composable
private fun IllustrationTile(illustration: HbIllustrationKind, modifier: Modifier = Modifier) {
    HbCard(
        modifier = modifier.widthIn(max = HbTheme.dimensions.illustrationWidth + HbTheme.spacing.xl * 2)
            .fillMaxWidth(),
    ) {
        HbColumn(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HbIllustration(illustration, contentDescription = null)
        }
        HbText(hbString(illustration.title()), style = HbTheme.typography.label)
        HbText(hbString(illustration.hint()), color = HbTheme.colors.textSecondary)
        SelectionContainer {
            HbText("HbIllustrationKind.${illustration.name}", style = HbTheme.typography.caption)
        }
    }
}

private fun HbIllustrationKind.title(): HbString = when (this) {
    HbIllustrationKind.Welcome -> HbString.IllustrationWelcome
    HbIllustrationKind.EmptyWorkspace -> HbString.IllustrationWorkspace
    HbIllustrationKind.EmptyChat -> HbString.IllustrationChat
    HbIllustrationKind.NoResults -> HbString.IllustrationSearch
    HbIllustrationKind.Success -> HbString.IllustrationSuccess
    HbIllustrationKind.Error -> HbString.IllustrationError
    HbIllustrationKind.Offline -> HbString.IllustrationOffline
    HbIllustrationKind.Upload -> HbString.IllustrationUpload
}

private fun HbIllustrationKind.hint(): HbString = when (this) {
    HbIllustrationKind.Welcome -> HbString.IllustrationWelcomeHint
    HbIllustrationKind.EmptyWorkspace -> HbString.IllustrationWorkspaceHint
    HbIllustrationKind.EmptyChat -> HbString.IllustrationChatHint
    HbIllustrationKind.NoResults -> HbString.IllustrationSearchHint
    HbIllustrationKind.Success -> HbString.IllustrationSuccessHint
    HbIllustrationKind.Error -> HbString.IllustrationErrorHint
    HbIllustrationKind.Offline -> HbString.IllustrationOfflineHint
    HbIllustrationKind.Upload -> HbString.IllustrationUploadHint
}

@Preview
@Composable
private fun IllustrationsLightPreview() {
    HbTheme(darkTheme = false) { IllustrationsCatalog() }
}

@Preview
@Composable
private fun IllustrationsDarkPreview() {
    HbTheme(darkTheme = true) { IllustrationsCatalog() }
}
