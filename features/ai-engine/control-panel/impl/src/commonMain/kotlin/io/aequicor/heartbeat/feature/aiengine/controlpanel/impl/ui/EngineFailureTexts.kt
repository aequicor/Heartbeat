package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_access
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_authentication
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_config_conflict
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_config_invalid
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_config_storage
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_config_unsupported
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_connection_changed
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_credentials_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_engine
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_limit
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_native_missing
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_network
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_project_unavailable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_quota
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_rejected
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.resources.control_failure_unknown
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource

/** Message of an engine failure. */
internal fun FailureUi.message(): StringResource = FailureMessages.getValue(this)

/** Every failure category with its message; a test checks that none is missing. */
internal val FailureMessages: Map<FailureUi, StringResource> = mapOf(
    FailureUi.Authentication to Res.string.control_failure_authentication,
    FailureUi.CredentialsMissing to Res.string.control_failure_credentials_missing,
    FailureUi.ConnectionChanged to Res.string.control_failure_connection_changed,
    FailureUi.Network to Res.string.control_failure_network,
    FailureUi.Limit to Res.string.control_failure_limit,
    FailureUi.Quota to Res.string.control_failure_quota,
    FailureUi.EngineUnavailable to Res.string.control_failure_engine,
    FailureUi.Access to Res.string.control_failure_access,
    FailureUi.Rejected to Res.string.control_failure_rejected,
    FailureUi.ConfigInvalid to Res.string.control_failure_config_invalid,
    FailureUi.ConfigConflict to Res.string.control_failure_config_conflict,
    FailureUi.ProjectUnavailable to Res.string.control_failure_project_unavailable,
    FailureUi.NativeMissing to Res.string.control_failure_native_missing,
    FailureUi.ConfigStorage to Res.string.control_failure_config_storage,
    FailureUi.ConfigUnsupported to Res.string.control_failure_config_unsupported,
    FailureUi.Unknown to Res.string.control_failure_unknown,
)

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
