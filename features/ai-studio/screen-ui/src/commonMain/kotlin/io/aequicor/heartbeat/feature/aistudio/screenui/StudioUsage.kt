package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbComposerUsageButton
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTooltip
import io.aequicor.heartbeat.ds.components.HbUsageSection
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_all_models
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_breakdown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_breakdown_hint
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_breakdown_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_chip_hours
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_chip_month
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_chip_week
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_compact
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_compact_short
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_compacting
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_consumed
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_context
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_context_tab
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_context_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_credits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_empty
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_expires
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_files
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_free_tokens
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_history
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_instructions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_limit_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_limits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_limits_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_percent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_remaining
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_remaining_total
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_reset_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_resets
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_stale
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_stale_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_token_unit
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_tokens
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_tokens_unknown_limit
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_tools
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_unallocated
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_unlimited
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_hours
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_minutes
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_month
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_unknown
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_week
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageCreditsUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.UsageWindowUi
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent as StudioIntent

/** Context details remain available for observed routes; provider limits appear only with reported metrics. */
@Composable
internal fun StudioUsage(content: PaneContent, onIntent: (StudioIntent) -> Unit) {
    val context = content.contextUsage
    val provider = content.providerUsage?.takeIf { it.isVisible }
    if (context == null && content.providerUsage == null && !content.isCompacting) return
    val window = provider?.indicatorWindow()
    val modelId = content.settings.modelId
    var isOpen by remember(content.pane.id, content.pane.sessionId, modelId) { mutableStateOf(false) }
    var section by remember(content.pane.id, content.pane.sessionId, modelId) { mutableStateOf(HbUsageSection.Context) }
    val isEmpty = context == null && provider == null && !content.isCompacting
    val accessible = contextAccessibility(context, content.isCompacting)
    HbComposerUsageButton(
        contextPercent = context?.percent,
        label = stringResource(
            if (context == null && provider != null) Res.string.usage_limits else Res.string.usage_context,
        ),
        isExpanded = isOpen,
        onExpandedChange = {
            if (it && !isOpen) onIntent(StudioIntent.RefreshUsage(modelId))
            isOpen = it
        },
        modifier = Modifier.testTag("usage-${content.pane.id}"),
        accessibleLabel = if (isEmpty) stringResource(Res.string.usage_empty) else accessible,
        quotaAccessibleLabel = quotaAccessibility(provider, window),
        hasContextData = context != null,
        hasQuotaData = provider != null,
        section = section,
        onSectionChange = { section = it },
        isCompacting = content.isCompacting,
        quotaPercent = window?.percent,
        quotaLabel = window?.let { windowChip(it) },
    ) {
        UsageDetails(content, context, provider, section, { section = it }) {
            isOpen = false
            onIntent(StudioIntent.CompactContext(content.pane.id))
        }
    }
}

@Composable
private fun UsageDetails(
    content: PaneContent,
    context: ContextUsageUi?,
    provider: ProviderUsageUi?,
    section: HbUsageSection,
    onSectionChange: (HbUsageSection) -> Unit,
    onCompact: () -> Unit,
) {
    if (context == null && provider == null && !content.isCompacting) {
        Column(verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.m)) {
            SecondaryText(stringResource(Res.string.usage_empty))
            if (content.isCompactOffered) ContextFooter(content, onCompact)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.m)) {
        UsageTabs(section, onSectionChange)
        when (section) {
            HbUsageSection.Context -> ContextSection(content, context, onCompact)

            HbUsageSection.Limits -> if (provider != null) {
                ProviderDetails(provider, content.calendar)
            } else {
                SecondaryText(stringResource(Res.string.usage_limits_unavailable))
            }
        }
    }
}

@Composable
private fun contextAccessibility(context: ContextUsageUi?, compacting: Boolean): String {
    val title = stringResource(Res.string.usage_title)
    val percent = context?.percent
    val detail = when {
        context == null -> stringResource(Res.string.usage_context_unavailable)
        percent == null -> stringResource(Res.string.usage_tokens_unknown_limit, context.usedTokens.groupedTokens())
        else -> "$title: ${stringResource(Res.string.usage_percent, percent)}"
    }
    return if (compacting) "$detail · ${stringResource(Res.string.usage_compacting)}" else detail
}

@Composable
private fun quotaAccessibility(provider: ProviderUsageUi?, window: UsageWindowUi?): String = if (window != null) {
    "${windowTitle(window)}: ${stringResource(Res.string.usage_percent, window.percent)}"
} else {
    stringResource(if (provider?.credits != null) Res.string.usage_credits else Res.string.usage_limits_unavailable)
}

@Composable
private fun UsageTabs(section: HbUsageSection, onSectionChange: (HbUsageSection) -> Unit) {
    val tabFocus = remember { HbUsageSection.entries.associateWith { FocusRequester() } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HbTheme.spacing.xs)) {
        HbUsageSection.entries.forEach { tab ->
            HbButton(
                text = stringResource(
                    if (tab == HbUsageSection.Context) Res.string.usage_context_tab else Res.string.usage_limits,
                ),
                onClick = { onSectionChange(tab) },
                modifier = Modifier.weight(1f).testTag("usage-tab-$tab")
                    .focusRequester(tabFocus.getValue(tab))
                    .semantics {
                        role = Role.Tab
                        selected = section == tab
                    }
                    .onPreviewKeyEvent { event -> switchUsageTab(event, section, onSectionChange, tabFocus) },
                style = if (section == tab) HbButtonStyle.Secondary else HbButtonStyle.Ghost,
                size = HbButtonSize.Small,
            )
        }
    }
}

private fun switchUsageTab(
    event: androidx.compose.ui.input.key.KeyEvent,
    section: HbUsageSection,
    onSectionChange: (HbUsageSection) -> Unit,
    focus: Map<HbUsageSection, FocusRequester>,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    if (event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) return false
    val target = if (section == HbUsageSection.Context) HbUsageSection.Limits else HbUsageSection.Context
    onSectionChange(target)
    focus.getValue(target).requestFocus()
    return true
}

@Composable
private fun ContextSection(content: PaneContent, context: ContextUsageUi?, onCompact: () -> Unit) {
    if (context != null) {
        ContextDetails(
            context,
        )
    } else {
        SecondaryText(stringResource(Res.string.usage_context_unavailable))
    }
    if (content.isCompacting) SecondaryText(stringResource(Res.string.usage_compacting))
    ContextFooter(content, onCompact)
}

@Composable
private fun ContextFooter(content: PaneContent, onCompact: () -> Unit) {
    HbDivider()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryText(stringResource(Res.string.usage_token_unit))
        if (content.isCompactOffered) {
            HbTooltip(stringResource(Res.string.usage_compact)) {
                HbButton(
                    text = stringResource(Res.string.usage_compact_short),
                    onClick = onCompact,
                    style = HbButtonStyle.Ghost,
                    size = HbButtonSize.Small,
                    enabled = content.isCompactReady,
                    modifier = Modifier.testTag("compact-${content.pane.id}"),
                )
            }
        }
    }
}

@Composable
private fun ContextDetails(usage: ContextUsageUi) {
    val capacity = usage.capacityTokens
    val tokens = if (capacity == null) {
        stringResource(Res.string.usage_tokens_unknown_limit, usage.usedTokens.groupedTokens())
    } else {
        stringResource(Res.string.usage_tokens, usage.usedTokens.groupedTokens(), capacity.groupedTokens())
    }
    UsageRow(tokens, usage.percent?.let { "$it%" }.orEmpty(), usage.percent)
    if (capacity == null) SecondaryText(stringResource(Res.string.usage_limit_unknown))
    usage.percent?.let { UsageProgress(it) }
    if (capacity != null) {
        SecondaryText(
            stringResource(
                Res.string.usage_free_tokens,
                (capacity - usage.usedTokens).coerceAtLeast(0).groupedTokens(),
            ),
        )
    }
    val breakdown = usage.breakdown
    val counts = listOf(breakdown?.instructions, breakdown?.history, breakdown?.tools, breakdown?.files)
    if (counts.all { it == null }) {
        HbDivider()
        HbText(stringResource(Res.string.usage_breakdown_unavailable))
        SecondaryText(stringResource(Res.string.usage_breakdown_hint))
    } else {
        SecondaryText(stringResource(Res.string.usage_breakdown))
        val labels = listOf(
            Res.string.usage_instructions,
            Res.string.usage_history,
            Res.string.usage_tools,
            Res.string.usage_files,
        )
        val colors = listOf(
            HbTheme.colors.dataViolet.copy(alpha = 0.4f),
            HbTheme.colors.dataViolet,
            HbTheme.colors.dataCyan,
            HbTheme.colors.textSecondary,
        )
        counts.forEachIndexed { index, count ->
            if (count != null) UsageRow(stringResource(labels[index]), count.groupedTokens(), marker = colors[index])
        }
        val remainder = usage.usedTokens - counts.filterNotNull().sum()
        if (remainder > 0) UsageRow(stringResource(Res.string.usage_unallocated), remainder.groupedTokens())
    }
}

@Composable
private fun ProviderDetails(usage: ProviderUsageUi, calendar: StudioCalendar) {
    SecondaryText(listOfNotNull(usage.planName, stringResource(Res.string.usage_limits)).joinToString(" · "))
    usage.windows.forEach { window ->
        Column(verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.xs)) {
            UsageRow(windowTitle(window), "${window.percent}%", window.percent)
            UsageProgress(window.percent)
            SecondaryText(
                window.resetsAt?.let {
                    stringResource(Res.string.usage_resets, calendar.usageDate(it))
                } ?: stringResource(Res.string.usage_reset_unknown),
            )
        }
    }
    usage.credits?.let {
        if (usage.windows.isNotEmpty()) HbDivider()
        CreditsDetails(it, calendar)
    }
    if (usage.isStale) {
        SecondaryText(
            usage.checkedAt?.let {
                stringResource(Res.string.usage_stale, calendar.usageDate(it))
            } ?: stringResource(Res.string.usage_stale_unknown),
        )
    }
}

@Composable
private fun windowTitle(window: UsageWindowUi): String {
    val scope = when (window.title) {
        "five_hour" -> null
        "seven_day" -> stringResource(Res.string.usage_all_models)
        "seven_day_opus" -> "Opus"
        "seven_day_sonnet" -> "Sonnet"
        else -> window.title.takeIf { it.isNotBlank() }
    }
    val minutes = window.windowDurationMinutes
    val duration = when {
        minutes == MINUTES_PER_MONTH -> stringResource(Res.string.usage_window_month)

        minutes == MINUTES_PER_WEEK -> stringResource(Res.string.usage_window_week)

        minutes != null && minutes > 0 && minutes % MINUTES_PER_HOUR == 0L ->
            stringResource(Res.string.usage_window_hours, minutes / MINUTES_PER_HOUR)

        minutes != null && minutes > 0 -> stringResource(Res.string.usage_window_minutes, minutes)

        else -> null
    }
    return listOfNotNull(
        duration,
        scope,
    ).joinToString(" · ").ifBlank { stringResource(Res.string.usage_window_unknown) }
}

/** The chip uses a localized period only; unknown durations never masquerade as a five-hour limit. */
@Composable
private fun windowChip(window: UsageWindowUi): String {
    val minutes = window.chipPeriodMinutes()
    return when {
        minutes == MINUTES_PER_WEEK -> stringResource(Res.string.usage_chip_week)

        minutes == MINUTES_PER_MONTH -> stringResource(Res.string.usage_chip_month)

        minutes != null && minutes > 0 && minutes % MINUTES_PER_HOUR == 0L ->
            stringResource(Res.string.usage_chip_hours, minutes / MINUTES_PER_HOUR)

        else -> "—"
    }
}

@Composable
private fun CreditsDetails(credits: UsageCreditsUi, calendar: StudioCalendar) {
    val label = if (credits.isUnlimited) {
        stringResource(Res.string.usage_unlimited)
    } else {
        val remaining = creditAmount(credits.remaining, credits.currency)
        if (credits.total != null) {
            stringResource(Res.string.usage_remaining_total, remaining, creditAmount(credits.total, credits.currency))
        } else {
            stringResource(Res.string.usage_remaining, remaining)
        }
    }
    HbText("${stringResource(Res.string.usage_credits)}: $label", style = HbTheme.typography.caption)
    val percent = credits.consumedPercent()
    val details = listOfNotNull(
        percent?.let { stringResource(Res.string.usage_consumed, it) },
        credits.expiresAt?.let { stringResource(Res.string.usage_expires, calendar.usageDate(it)) },
    )
    if (details.isNotEmpty()) SecondaryText(details.joinToString(" · "))
}

@Composable
private fun UsageRow(title: String, value: String, percent: Int? = null, marker: Color? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HbTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marker != null) {
            HbText(
                "●",
                modifier = Modifier.clearAndSetSemantics { },
                style = HbTheme.typography.caption,
                color = marker,
            )
        }
        HbText(title, modifier = Modifier.weight(1f), style = HbTheme.typography.caption, maxLines = 1)
        HbText(value, style = HbTheme.typography.caption, color = HbTheme.colors.usageColor(percent))
    }
}

@Composable
private fun UsageProgress(percent: Int) {
    HbProgressBar(
        progress = percent / PERCENT_MAX,
        contentDescription = stringResource(Res.string.usage_percent, percent),
        color = HbTheme.colors.usageColor(percent),
    )
}

@Composable
private fun SecondaryText(text: String) {
    HbText(text, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
}

private fun StudioCalendar.usageDate(instant: Instant): String = "${day(instant)} ${timeLabel(instant)}"

private fun creditAmount(value: Double?, currency: String?): String {
    val number = value?.toString()?.removeSuffix(".0").orEmpty()
    return listOfNotNull(number, currency?.takeIf { it.isNotBlank() }).joinToString(" ")
}

private fun Long.groupedTokens(): String = toString().reversed().chunked(DIGIT_GROUP_SIZE).joinToString(" ").reversed()

private const val PERCENT_MAX = 100f
private const val DIGIT_GROUP_SIZE = 3
