package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ColumnUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.GapUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.OptionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.PairUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.RowUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ToneUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import kotlinx.collections.immutable.persistentListOf

@Preview
@Composable
private fun SurfaceLightPreview() {
    HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) { PreviewSurface() }
}

@Preview
@Composable
private fun SurfaceDarkPreview() {
    HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) { PreviewSurface() }
}

@Preview
@Composable
private fun SurfaceMobilePreview() {
    HbTheme(dimensions = HbDimensions.Mobile) { PreviewSurface() }
}

@Composable
private fun PreviewSurface() {
    HbColumn(Modifier.background(HbTheme.colors.background).padding(HbTheme.spacing.xl)) {
        SurfaceContent(PreviewState, {})
    }
}

private val PreviewState = SurfaceState(
    root = UiNodeUi.Column(
        "b",
        persistentListOf(
            UiNodeUi.Text("title", "Pull requests", TextStyleUi.Heading, ToneUi.Neutral),
            UiNodeUi.Row(
                "badges",
                persistentListOf(
                    UiNodeUi.Badge("open", "3 open", ToneUi.Success),
                    UiNodeUi.Badge("blocked", "1 blocked", ToneUi.Danger),
                ),
                GapUi.Small,
                isWrapped = true,
            ),
            UiNodeUi.Table(
                "prs",
                persistentListOf(ColumnUi("#", 1f), ColumnUi("Title", 4f)),
                persistentListOf(
                    RowUi("12", persistentListOf("12", "Draw the pull request map"), press = null),
                    RowUi("15", persistentListOf("15", "Fix the login redirect on slow networks"), press = null),
                ),
                emptyText = null,
                hidden = 0,
            ),
            UiNodeUi.Progress("checks", 0.6f, "Checks"),
            UiNodeUi.KeyValue("info", persistentListOf(PairUi("Base", "master"), PairUi("Reviewers", "2 of 3"))),
            UiNodeUi.Toggle("drafts", "drafts", "Drafts only", isChecked = true),
            UiNodeUi.Select(
                "priority",
                "priority",
                "Priority",
                persistentListOf(OptionUi("high", "High"), OptionUi("low", "Low")),
                "high",
            ),
            UiNodeUi.Native("due", "due", NativeWidgetUi.Date, "Due", "2026-10-12", persistentListOf()),
        ),
        GapUi.Medium,
    ),
    notice = NoticeUi.Message("Two pull requests wait for review"),
)
