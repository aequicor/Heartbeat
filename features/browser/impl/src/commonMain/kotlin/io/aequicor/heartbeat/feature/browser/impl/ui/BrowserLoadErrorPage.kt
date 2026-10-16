package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenState
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserSecurityUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_advanced
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_back
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_blocked
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_description
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_proceed
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_title
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_description
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_details
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_dns
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_offline
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_redirects
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_refused
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_reset
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_timeout
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_title
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_reload
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_https
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_insecure
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Replaces the native viewport. Advanced certificate actions are never visible before explicit expansion. */
@Composable
internal fun BrowserLoadErrorPage(
    state: BrowserScreenState,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val failure = state.failure ?: return
    val isCertificate = failure.isCertificate
    HbColumn(
        modifier.widthIn(max = HbTheme.dimensions.messageMaxWidth).fillMaxWidth()
            .hbVerticalScroll(rememberScrollState()).padding(HbTheme.spacing.xxl).testTag("browser-failure-page"),
        gap = HbTheme.spacing.l,
    ) {
        HbIcon(HbIcons.Warning, contentDescription = null, tint = HbTheme.colors.textSecondary)
        HbText(
            stringResource(
                if (isCertificate) Res.string.browser_certificate_title else Res.string.browser_failure_title,
            ),
            style = HbTheme.typography.title,
        )
        HbText(failure.url, color = HbTheme.colors.textSecondary)
        HbText(
            stringResource(
                if (isCertificate) {
                    Res.string.browser_certificate_description
                } else {
                    Res.string.browser_failure_description
                },
            ),
        )
        HbText(
            if (isCertificate) "NET::${failure.code}" else failure.code,
            Modifier.testTag("browser-failure-code"),
            color = HbTheme.colors.textSecondary,
        )
        BrowserFailurePrimaryAction(state, onIntent)
        HbButton(
            stringResource(
                if (isCertificate) Res.string.browser_certificate_advanced else Res.string.browser_failure_details,
            ),
            { onIntent(BrowserScreenIntent.ToggleErrorDetails) },
            Modifier.testTag("browser-failure-details"),
            style = HbButtonStyle.Ghost,
        )
        if (state.isErrorDetailsExpanded) BrowserFailureDetails(state, onIntent)
    }
}

@Composable
private fun BrowserFailurePrimaryAction(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    val failure = state.failure ?: return
    if (failure.isCertificate) {
        HbButton(
            stringResource(Res.string.browser_certificate_back),
            { state.selectedTabId?.let { onIntent(BrowserScreenIntent.ReturnToSafety(it, failure)) } },
            Modifier.testTag("browser-back-to-safety"),
        )
    } else {
        HbButton(
            stringResource(Res.string.browser_reload),
            { onIntent(BrowserScreenIntent.Reload) },
            Modifier.testTag("browser-failure-reload"),
        )
    }
}

@Composable
private fun BrowserFailureDetails(state: BrowserScreenState, onIntent: (BrowserScreenIntent) -> Unit) {
    val failure = state.failure ?: return
    if (!failure.isCertificate) {
        HbText(stringResource(failure.code.details()))
    } else if (failure.isProceedAllowed) {
        HbButton(
            stringResource(Res.string.browser_certificate_proceed),
            { state.selectedTabId?.let { onIntent(BrowserScreenIntent.ProceedCertificate(it, failure)) } },
            Modifier.testTag("browser-proceed-certificate"),
            style = HbButtonStyle.Ghost,
        )
    } else {
        HbText(stringResource(Res.string.browser_certificate_blocked))
    }
}

private fun String.details(): StringResource = when (this) {
    "ERR_NAME_NOT_RESOLVED" -> Res.string.browser_failure_dns
    "ERR_CONNECTION_REFUSED" -> Res.string.browser_failure_refused
    "ERR_CONNECTION_TIMED_OUT" -> Res.string.browser_failure_timeout
    "ERR_CONNECTION_RESET" -> Res.string.browser_failure_reset
    "ERR_TOO_MANY_REDIRECTS" -> Res.string.browser_failure_redirects
    "ERR_INTERNET_DISCONNECTED" -> Res.string.browser_failure_offline
    else -> Res.string.browser_failure_description
}

/** HTTPS uses a neutral information icon, never a guarantee that the site itself is trustworthy. */
@Composable
internal fun BrowserSecurityIndicator(security: BrowserSecurityUi, modifier: Modifier = Modifier) {
    val isInsecure = security == BrowserSecurityUi.Http || security == BrowserSecurityUi.CertificateException
    val color = if (security ==
        BrowserSecurityUi.CertificateException
    ) {
        HbTheme.colors.errorForeground
    } else {
        HbTheme.colors.textSecondary
    }
    HbRow(modifier.testTag("browser-security"), gap = HbTheme.spacing.xs) {
        HbIcon(
            when (security) {
                BrowserSecurityUi.Unknown -> HbIcons.Globe
                BrowserSecurityUi.Https, BrowserSecurityUi.Http -> HbIcons.Info
                BrowserSecurityUi.CertificateException -> HbIcons.Warning
            },
            contentDescription = if (security == BrowserSecurityUi.Https) {
                stringResource(
                    Res.string.browser_security_https,
                )
            } else {
                null
            },
            tint = color,
        )
        if (isInsecure) HbText(stringResource(Res.string.browser_security_insecure), color = color, maxLines = 1)
    }
}
