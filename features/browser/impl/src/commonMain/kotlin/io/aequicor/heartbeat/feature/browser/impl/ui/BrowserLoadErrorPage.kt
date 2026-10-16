package io.aequicor.heartbeat.feature.browser.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserFailureUi
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserScreenIntent
import io.aequicor.heartbeat.feature.browser.impl.presentation.store.BrowserSecurityUi
import io.aequicor.heartbeat.feature.browser.impl.resources.Res
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_advanced
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_agent
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_back
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_blocked
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_description
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_proceed
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_certificate_title
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_description
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_details
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_details_collapsed
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_details_expanded
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_dns
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_offline
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_redirects
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_refused
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_reset
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_timeout
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_failure_title
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_reload
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_certificate_exception
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_https
import io.aequicor.heartbeat.feature.browser.impl.resources.browser_security_insecure
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Replaces the native viewport. Advanced certificate actions are never visible before explicit expansion. */
@Composable
internal fun BrowserLoadErrorPage(
    failure: BrowserFailureUi,
    isDetailsExpanded: Boolean,
    onIntent: (BrowserScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            Modifier.semantics { heading() },
            style = HbTheme.typography.title,
        )
        HbText(failure.url, color = HbTheme.colors.textSecondary, maxLines = URL_MAX_LINES)
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
            if (isCertificate) "$CERTIFICATE_CODE_PREFIX${failure.code}" else failure.code,
            Modifier.testTag("browser-failure-code"),
            color = HbTheme.colors.textSecondary,
        )
        if (isCertificate && !failure.isDecisionAvailable) {
            // An agent controls the tab: the machine rejects a person's decision until control is taken.
            HbText(stringResource(Res.string.browser_certificate_agent), Modifier.testTag("browser-failure-agent"))
        } else {
            BrowserFailureActions(failure, isDetailsExpanded, onIntent)
        }
    }
}

@Composable
private fun BrowserFailureActions(
    failure: BrowserFailureUi,
    isDetailsExpanded: Boolean,
    onIntent: (BrowserScreenIntent) -> Unit,
) {
    val isCertificate = failure.isCertificate
    BrowserFailurePrimaryAction(failure, onIntent)
    val details = if (isCertificate) null else failure.code.details()
    if (isCertificate || details != null) {
        BrowserFailureDetailsToggle(isCertificate, isDetailsExpanded, onIntent)
    }
    if (isDetailsExpanded) {
        when {
            details != null -> HbText(stringResource(details))

            failure.isProceedAllowed -> HbButton(
                stringResource(Res.string.browser_certificate_proceed),
                { onIntent(BrowserScreenIntent.ProceedCertificate(failure)) },
                Modifier.testTag("browser-proceed-certificate"),
                style = HbButtonStyle.Ghost,
            )

            isCertificate -> HbText(stringResource(Res.string.browser_certificate_blocked))
        }
    }
}

@Composable
private fun BrowserFailurePrimaryAction(failure: BrowserFailureUi, onIntent: (BrowserScreenIntent) -> Unit) {
    if (failure.isCertificate) {
        HbButton(
            stringResource(Res.string.browser_certificate_back),
            { onIntent(BrowserScreenIntent.ReturnToSafety(failure)) },
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

/** Announces its expansion: the revealed section may hold the unsafe bypass. */
@Composable
private fun BrowserFailureDetailsToggle(
    isCertificate: Boolean,
    isExpanded: Boolean,
    onIntent: (BrowserScreenIntent) -> Unit,
) {
    val expansion = stringResource(
        if (isExpanded) Res.string.browser_failure_details_expanded else Res.string.browser_failure_details_collapsed,
    )
    val label = if (isCertificate) Res.string.browser_certificate_advanced else Res.string.browser_failure_details
    HbButton(
        stringResource(label),
        { onIntent(BrowserScreenIntent.ToggleErrorDetails) },
        Modifier.testTag("browser-failure-details").semantics { stateDescription = expansion },
        style = HbButtonStyle.Ghost,
    )
}

/** Codes without a specific explanation have no details beyond the page's own description. */
private fun String.details(): StringResource? = when (this) {
    "ERR_NAME_NOT_RESOLVED" -> Res.string.browser_failure_dns
    "ERR_CONNECTION_REFUSED" -> Res.string.browser_failure_refused
    "ERR_CONNECTION_TIMED_OUT" -> Res.string.browser_failure_timeout
    "ERR_CONNECTION_RESET" -> Res.string.browser_failure_reset
    "ERR_TOO_MANY_REDIRECTS" -> Res.string.browser_failure_redirects
    "ERR_INTERNET_DISCONNECTED" -> Res.string.browser_failure_offline
    else -> null
}

/** HTTPS uses a neutral information icon, never a guarantee that the site itself is trustworthy. */
@Composable
internal fun BrowserSecurityIndicator(security: BrowserSecurityUi, modifier: Modifier = Modifier) {
    val isException = security == BrowserSecurityUi.CertificateException
    val color = if (isException) HbTheme.colors.errorForeground else HbTheme.colors.textSecondary
    val description = when (security) {
        BrowserSecurityUi.Https -> stringResource(Res.string.browser_security_https)
        BrowserSecurityUi.CertificateException -> stringResource(Res.string.browser_security_certificate_exception)
        BrowserSecurityUi.Unknown, BrowserSecurityUi.Http -> null
    }
    val icon = when (security) {
        BrowserSecurityUi.Unknown -> HbIcons.Globe
        BrowserSecurityUi.Https, BrowserSecurityUi.Http -> HbIcons.Info
        BrowserSecurityUi.CertificateException -> HbIcons.Warning
    }
    HbRow(modifier.testTag("browser-security"), gap = HbTheme.spacing.xs) {
        HbIcon(icon, contentDescription = description, tint = color)
        if (isException || security == BrowserSecurityUi.Http) {
            HbText(stringResource(Res.string.browser_security_insecure), color = color, maxLines = 1)
        }
    }
}

private const val URL_MAX_LINES = 3

/** Chrome's technical prefix for certificate error codes; not localized. */
private const val CERTIFICATE_CODE_PREFIX = "NET::"
