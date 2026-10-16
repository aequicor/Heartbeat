package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

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
