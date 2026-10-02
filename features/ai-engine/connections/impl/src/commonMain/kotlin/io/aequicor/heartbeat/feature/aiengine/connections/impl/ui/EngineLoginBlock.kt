package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCopyButton
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenIntent
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LoginMethodUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LoginUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ManagementFailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.Res
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_copy_failed
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_code
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_code_copied
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_code_copy
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_code_label
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_code_placeholder
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_device
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_open_page
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_signed_in
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_signed_in_as
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_signed_out
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_submit
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_terminal
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_terminal_copied
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_terminal_copy
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_login_title
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_logout
import io.aequicor.heartbeat.feature.aiengine.connections.impl.resources.engine_not_inspected
import org.jetbrains.compose.resources.stringResource

/**
 * CLI sign-in of the selected engine: the account, sign-in and sign-out, and while a sign-in runs the page to open,
 * the device code to enter there or a field for the code the page shows. A CLI that signs in only from a terminal
 * leaves the command to copy. URLs and codes are shown, never logged.
 */
@Composable
internal fun EngineLoginBlock(
    panel: EnginePanelUi,
    login: LoginUi,
    loginCode: String,
    isIdle: Boolean,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbColumn(modifier.fillMaxWidth().testTag("engine-login"), gap = HbTheme.spacing.s) {
        HbSettingsRow(stringResource(Res.string.engine_login_title), description = loginText(login)) {
            if (EngineActionKindUi.Logout in panel.actions) {
                ActionButton(stringResource(Res.string.engine_logout), "engine-logout", isIdle) {
                    onIntent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Logout))
                }
            }
        }
        if (EngineActionKindUi.Login in panel.actions) {
            HbFlowRow(Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m)) {
                ActionButton(
                    stringResource(Res.string.engine_login),
                    "engine-login-browser",
                    isIdle,
                    style = HbButtonStyle.Primary,
                ) {
                    onIntent(EngineConnectionsScreenIntent.RequestEngineAction(EngineActionUi.Login))
                }
                if (LoginMethodUi.DeviceCode in panel.loginMethods) {
                    ActionButton(stringResource(Res.string.engine_login_device), "engine-login-device", isIdle) {
                        onIntent(
                            EngineConnectionsScreenIntent.RequestEngineAction(
                                EngineActionUi.Login,
                                LoginMethodUi.DeviceCode,
                            ),
                        )
                    }
                }
            }
        }
        val job = panel.job?.takeIf { it.action == EngineActionUi.Login }
        when (val phase = job?.phase) {
            is JobPhaseUi.AwaitingBrowser -> BrowserPrompt(phase.url, phase.userCode)

            is JobPhaseUi.AwaitingCode -> CodePrompt(phase.url, loginCode, onIntent)

            is JobPhaseUi.Failed -> (phase.failure as? ManagementFailureUi.Login)?.terminalCommand?.let {
                TerminalCommand(
                    it,
                )
            }

            else -> Unit
        }
    }
}

@Composable
private fun BrowserPrompt(url: String, userCode: String?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    HbColumn(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.s) {
        ActionButton(stringResource(Res.string.engine_login_open_page), "engine-login-open", enabled = true) {
            openProviderPage(uriHandler, url)
        }
        if (userCode != null) {
            HbRow(gap = HbTheme.spacing.s) {
                HbText(stringResource(Res.string.engine_login_code), color = HbTheme.colors.textSecondary)
                HbText(userCode, Modifier.testTag("engine-login-user-code"), style = HbTheme.typography.code)
                HbCopyButton(
                    userCode,
                    stringResource(Res.string.engine_login_code_copy),
                    stringResource(Res.string.engine_login_code_copied),
                    stringResource(Res.string.engine_copy_failed),
                )
            }
        }
    }
}

@Composable
private fun CodePrompt(
    url: String?,
    code: String,
    onIntent: (EngineConnectionsScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val label = stringResource(Res.string.engine_login_code_label)
    HbColumn(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.xs) {
        HbText(label, style = HbTheme.typography.label)
        HbTextField(
            code,
            { onIntent(EngineConnectionsScreenIntent.EditLoginCode(it)) },
            Modifier.fillMaxWidth().testTag("engine-login-code"),
            placeholder = stringResource(Res.string.engine_login_code_placeholder),
            accessibleLabel = label,
            isSecret = true,
        )
        HbFlowRow {
            ActionButton(
                stringResource(Res.string.engine_login_submit),
                "engine-login-submit",
                enabled = code.isNotBlank(),
                style = HbButtonStyle.Primary,
            ) { onIntent(EngineConnectionsScreenIntent.SubmitLoginCode) }
            if (url != null) {
                ActionButton(stringResource(Res.string.engine_login_open_page), "engine-login-open", enabled = true) {
                    openProviderPage(uriHandler, url)
                }
            }
        }
    }
}

@Composable
private fun TerminalCommand(command: String, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.m), gap = HbTheme.spacing.xs) {
        HbText(
            stringResource(Res.string.engine_login_terminal),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbRow(gap = HbTheme.spacing.s) {
            HbText(command, Modifier.weight(1f).testTag("engine-login-terminal"), style = HbTheme.typography.code)
            HbCopyButton(
                command,
                stringResource(Res.string.engine_login_terminal_copy),
                stringResource(Res.string.engine_login_terminal_copied),
                stringResource(Res.string.engine_copy_failed),
            )
        }
    }
}

@Composable
private fun loginText(login: LoginUi): String = when (login) {
    LoginUi.Unknown -> stringResource(Res.string.engine_not_inspected)

    LoginUi.SignedOut -> stringResource(Res.string.engine_login_signed_out)

    is LoginUi.SignedIn -> login.account?.let { stringResource(Res.string.engine_login_signed_in_as, it) }
        ?: stringResource(Res.string.engine_login_signed_in)
}
