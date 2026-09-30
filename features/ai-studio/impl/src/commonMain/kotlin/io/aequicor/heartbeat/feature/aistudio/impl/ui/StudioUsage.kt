package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbComposerUsageButton
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbProgressBar
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ContextUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProviderUsageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.UsageCreditsUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.UsageWindowUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_all_models
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_context
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_credits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_expires
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_last_measurement
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_limits
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_percent
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_remaining
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_remaining_total
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_resets
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_stale
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_title
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_tokens
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_unlimited
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_hours
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_minutes
import io.aequicor.heartbeat.feature.aistudio.impl.resources.usage_window_week
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Context is per conversation; a quota-only connection still offers the same details panel. */
@Composable
internal fun StudioUsage(content: PaneContent, onIntent: (AiStudioScreenIntent) -> Unit) {
    val context = content.contextUsage
    val provider = content.providerUsage?.takeIf { it.isVisible }
    if (context == null && provider == null) return
    val modelId = content.session?.modelId ?: content.settings.modelId
    var isOpen by remember(content.pane.id, content.pane.sessionId, modelId) { mutableStateOf(false) }
    val title = stringResource(Res.string.usage_title)
    val accessible = context?.let { "$title: ${stringResource(Res.string.usage_percent, it.percent)}" } ?: title
    HbComposerUsageButton(
        contextPercent = context?.percent,
        label = stringResource(Res.string.usage_limits),
        isExpanded = isOpen,
        onExpandedChange = {
            isOpen = it
            if (it) onIntent(AiStudioScreenIntent.RefreshUsage(modelId))
        },
        modifier = Modifier.testTag("usage-${content.pane.id}"),
        accessibleLabel = accessible,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.l)) {
            if (context != null) ContextDetails(context)
            if (context != null && provider != null) HbDivider()
            if (provider != null) ProviderDetails(provider, content.calendar)
        }
    }
}

@Composable
private fun ContextDetails(usage: ContextUsageUi) {
    UsageRow(stringResource(Res.string.usage_context), "${usage.percent}%")
    UsageProgress(usage.percent)
    SecondaryText(stringResource(Res.string.usage_tokens, usage.usedTokens.toString(), usage.capacityTokens.toString()))
    SecondaryText(stringResource(Res.string.usage_last_measurement))
}

@Composable
private fun ProviderDetails(usage: ProviderUsageUi, calendar: StudioCalendar) {
    HbText(listOfNotNull(stringResource(Res.string.usage_limits), usage.planName).joinToString(" · "))
    usage.windows.forEach { window ->
        Column(verticalArrangement = Arrangement.spacedBy(HbTheme.spacing.xs)) {
            UsageRow(windowTitle(window), "${window.percent}%")
            UsageProgress(window.percent)
            window.resetsAt?.let { SecondaryText(stringResource(Res.string.usage_resets, calendar.usageDate(it))) }
        }
    }
    usage.credits?.let {
        if (usage.windows.isNotEmpty()) HbDivider()
        CreditsDetails(it, calendar)
    }
    if (usage.isStale) {
        usage.checkedAt?.let { SecondaryText(stringResource(Res.string.usage_stale, calendar.usageDate(it))) }
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
        minutes == MINUTES_PER_WEEK -> stringResource(Res.string.usage_window_week)

        minutes != null && minutes > 0 && minutes % MINUTES_PER_HOUR == 0L ->
            stringResource(Res.string.usage_window_hours, minutes / MINUTES_PER_HOUR)

        minutes != null && minutes > 0 -> stringResource(Res.string.usage_window_minutes, minutes)

        else -> null
    }
    return listOfNotNull(duration, scope).joinToString(" · ")
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
    UsageRow(stringResource(Res.string.usage_credits), label)
    credits.expiresAt?.let { SecondaryText(stringResource(Res.string.usage_expires, calendar.usageDate(it))) }
}

@Composable
private fun UsageRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HbTheme.spacing.m)) {
        HbText(title, modifier = Modifier.weight(1f), maxLines = 1)
        HbText(value, color = HbTheme.colors.textSecondary)
    }
}

@Composable
private fun UsageProgress(percent: Int) {
    HbProgressBar(
        progress = percent / PERCENT_MAX,
        contentDescription = stringResource(Res.string.usage_percent, percent),
        color = when {
            percent >= CRITICAL_PERCENT -> HbTheme.colors.error
            percent >= WARNING_PERCENT -> HbTheme.colors.warning
            else -> HbTheme.colors.brand
        },
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

private const val PERCENT_MAX = 100f
private const val WARNING_PERCENT = 80
private const val CRITICAL_PERCENT = 100

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_WEEK = 10080L
