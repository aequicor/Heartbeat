package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_access
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_authentication
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_connection_changed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_credentials_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_engine
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_limit
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_network
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_quota
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_rejected
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_unknown
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource

/** Message of an engine failure. */
internal fun FailureUi.message(): StringResource = when (this) {
    FailureUi.Authentication -> Res.string.control_failure_authentication
    FailureUi.CredentialsMissing -> Res.string.control_failure_credentials_missing
    FailureUi.ConnectionChanged -> Res.string.control_failure_connection_changed
    FailureUi.Network -> Res.string.control_failure_network
    FailureUi.Limit -> Res.string.control_failure_limit
    FailureUi.Quota -> Res.string.control_failure_quota
    FailureUi.EngineUnavailable -> Res.string.control_failure_engine
    FailureUi.Access -> Res.string.control_failure_access
    FailureUi.Rejected -> Res.string.control_failure_rejected
    FailureUi.Unknown -> Res.string.control_failure_unknown
}

/** Opens a provider's sign-in or account page; a host without a browser leaves it unopened. */
internal fun openProviderPage(uriHandler: UriHandler, page: String) {
    textsLog.i { "open provider page" }
    try {
        uriHandler.openUri(page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        textsLog.w(e) { "provider page could not be opened" }
    }
}

private val textsLog = Log.tag("EngineControlTexts")
