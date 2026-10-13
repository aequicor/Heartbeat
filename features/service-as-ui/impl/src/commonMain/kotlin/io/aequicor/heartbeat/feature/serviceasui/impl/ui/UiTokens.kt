package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ButtonStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.GapUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ToneUi

/** Spacing token of a gap; "none" keeps the smallest token so siblings never touch. */
@Composable
@ReadOnlyComposable
internal fun GapUi.spacing(): Dp = when (this) {
    GapUi.None -> HbTheme.spacing.xxs
    GapUi.Small -> HbTheme.spacing.s
    GapUi.Medium -> HbTheme.spacing.l
    GapUi.Large -> HbTheme.spacing.xl
}

/**
 * Typography of a text role: titles and headings are semibold, and a [tone] other than neutral emphasizes other text
 * by weight. Text keeps the text colors: the status colors of the palette are container accents too light for text,
 * so tones color badges and banners, whose pairs pass the contrast tests.
 */
@Composable
@ReadOnlyComposable
internal fun TextStyleUi.style(tone: ToneUi = ToneUi.Neutral): TextStyle {
    val typography = HbTheme.typography
    return when (this) {
        TextStyleUi.Title -> typography.label.copy(fontWeight = FontWeight.SemiBold)
        TextStyleUi.Heading -> typography.title.copy(fontWeight = FontWeight.SemiBold)
        TextStyleUi.Body -> typography.body.emphasized(tone)
        TextStyleUi.Caption -> typography.caption.emphasized(tone)
        TextStyleUi.Code -> typography.code
    }
}

/** Text color of a role: captions are secondary text. */
@Composable
@ReadOnlyComposable
internal fun TextStyleUi.color(): Color =
    if (this == TextStyleUi.Caption) HbTheme.colors.textSecondary else HbTheme.colors.textPrimary

/** Whether text of this role titles what follows it, for screen readers. */
internal val TextStyleUi.isHeading: Boolean
    get() = this == TextStyleUi.Title || this == TextStyleUi.Heading

private fun TextStyle.emphasized(tone: ToneUi): TextStyle =
    if (tone == ToneUi.Neutral) this else copy(fontWeight = FontWeight.Medium)

/** Badge tone of the design system. */
internal fun ToneUi.hb(): HbTone = when (this) {
    ToneUi.Neutral -> HbTone.Neutral
    ToneUi.Brand -> HbTone.Brand
    ToneUi.Success -> HbTone.Success
    ToneUi.Warning -> HbTone.Warning
    ToneUi.Danger -> HbTone.Danger
}

/** Button style of the design system. */
internal fun ButtonStyleUi.hb(): HbButtonStyle = when (this) {
    ButtonStyleUi.Primary -> HbButtonStyle.Primary
    ButtonStyleUi.Secondary -> HbButtonStyle.Secondary
    ButtonStyleUi.Ghost -> HbButtonStyle.Ghost
    ButtonStyleUi.Danger -> HbButtonStyle.Danger
}

/** Icon a spec names, or null for an unknown name. */
internal fun iconOf(name: String?): ImageVector? = name?.lowercase()?.let { Icons[it] }

private val Icons: Map<String, ImageVector> = mapOf(
    "play" to HbIcons.Play,
    "pause" to HbIcons.Pause,
    "stop" to HbIcons.Stop,
    "refresh" to HbIcons.Refresh,
    "copy" to HbIcons.Copy,
    "link" to HbIcons.Link,
    "external" to HbIcons.ExternalLink,
    "open" to HbIcons.ExternalLink,
    "folder" to HbIcons.Folder,
    "file" to HbIcons.File,
    "code" to HbIcons.Code,
    "terminal" to HbIcons.Terminal,
    "calendar" to HbIcons.Calendar,
    "search" to HbIcons.Search,
    "filter" to HbIcons.Filter,
    "sort" to HbIcons.Sort,
    "edit" to HbIcons.Edit,
    "trash" to HbIcons.Trash,
    "download" to HbIcons.Download,
    "upload" to HbIcons.Upload,
    "share" to HbIcons.Share,
    "send" to HbIcons.Send,
    "settings" to HbIcons.Settings,
    "info" to HbIcons.Info,
    "warning" to HbIcons.Warning,
    "success" to HbIcons.Success,
    "error" to HbIcons.Error,
    "check" to HbIcons.Check,
    "close" to HbIcons.Close,
    "plus" to HbIcons.Plus,
    "minus" to HbIcons.Minus,
    "pull-request" to HbIcons.PullRequest,
    "merge" to HbIcons.Merge,
    "branch" to HbIcons.Branch,
    "commit" to HbIcons.Commit,
    "globe" to HbIcons.Globe,
    "database" to HbIcons.Database,
    "cloud" to HbIcons.Cloud,
    "user" to HbIcons.User,
    "users" to HbIcons.Users,
    "mail" to HbIcons.Mail,
    "bell" to HbIcons.Bell,
    "star" to HbIcons.Star,
    "heart" to HbIcons.Heart,
    "sparkles" to HbIcons.Sparkles,
    "plan" to HbIcons.Plan,
    "history" to HbIcons.History,
    "bookmark" to HbIcons.Bookmark,
    "tag" to HbIcons.Tag,
    "pin" to HbIcons.Pin,
    "lock" to HbIcons.Lock,
    "eye" to HbIcons.Eye,
    "chat" to HbIcons.Chat,
    "grid" to HbIcons.Grid,
)
