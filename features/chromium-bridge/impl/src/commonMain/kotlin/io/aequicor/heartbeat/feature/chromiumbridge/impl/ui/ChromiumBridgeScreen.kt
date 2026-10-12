package io.aequicor.heartbeat.feature.chromiumbridge.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.AvailabilityUi
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.BrowserRow
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.BrowserUi
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ChromiumBridgeModel
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ChromiumBridgeScreenIntent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ChromiumBridgeScreenState
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ConnectionRow
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.FailureUi
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.RegistrationError
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.RegistrationUi
import io.aequicor.heartbeat.feature.chromiumbridge.impl.presentation.ServerUi
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.Res
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_back
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_browser_brave
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_browser_chrome
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_browser_chromium
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_browser_edge
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_browser_vivaldi
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_browsers_hint
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_connection
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_connection_agent
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_connection_title
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_controlled_tabs
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_controlled_tabs_count
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_disabled
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_dismiss
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_extension_folder
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_extension_folder_missing
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_extension_hint
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_extension_id
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_failure
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_failure_launcher
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_failure_platform
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_failure_translocated
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_failure_write
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_loading
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_no_browsers
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_no_connections
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_register
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_register_description
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_registration_current
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_registration_missing
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_registration_none
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_registration_other
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_rescan
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_reveal
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_revoke
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_section_agents
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_section_browsers
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_section_connection
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_section_extension
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_server
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_server_failed
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_server_listening
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_server_occupied
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_server_starting
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_title
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_unregister
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_unregister_description
import io.aequicor.heartbeat.feature.chromiumbridge.impl.resources.chromium_bridge_unsupported
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/** Chromium browser settings: bridge status, connected extensions, host registration and agent tabs. */
@Composable
internal fun ChromiumBridgeScreen(model: ChromiumBridgeModel, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by produceState(ChromiumBridgeScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    ChromiumBridgeContent(state, model.store::intent, onBack, modifier)
}

/** Stateless content of [ChromiumBridgeScreen]. */
@Composable
internal fun ChromiumBridgeContent(
    state: ChromiumBridgeScreenState,
    onIntent: (ChromiumBridgeScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("chromium-bridge"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.chromium_bridge_title),
                leadingInset = if (HbTheme.dimensions.isDesktop) {
                    HbTheme.spacing.m
                } else {
                    HbTheme.dimensions.titlebarLeadingInset
                },
                navigation = {
                    HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.chromium_bridge_back), onBack)
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                when (state.availability) {
                    AvailabilityUi.Loading -> notice(Res.string.chromium_bridge_loading, HbTone.Neutral)
                    AvailabilityUi.Disabled -> notice(Res.string.chromium_bridge_disabled, HbTone.Warning)
                    AvailabilityUi.Unsupported -> notice(Res.string.chromium_bridge_unsupported, HbTone.Warning)
                    AvailabilityUi.Enabled -> enabledItems(state, onIntent)
                }
            }
        }
    }
}

private fun LazyListScope.notice(message: StringResource, tone: HbTone) {
    item(key = "notice") { HbBanner(stringResource(message), Modifier.testTag("chromium-bridge-notice"), tone = tone) }
}

private fun LazyListScope.enabledItems(
    state: ChromiumBridgeScreenState,
    onIntent: (ChromiumBridgeScreenIntent) -> Unit,
) {
    state.error?.let { error -> item(key = "error") { ErrorBanner(error, onIntent) } }
    item(key = "connection") { ConnectionSection(state) }
    item(key = "browsers") { BrowsersSection(state, onIntent) }
    item(key = "extension") { ExtensionSection(state, onIntent) }
    if (state.isAgentControlEnabled) item(key = "agents") { AgentsSection(state, onIntent) }
}

@Composable
private fun ErrorBanner(error: RegistrationError, onIntent: (ChromiumBridgeScreenIntent) -> Unit) {
    val browser = stringResource(error.browser.title())
    HbBanner(
        stringResource(Res.string.chromium_bridge_failure, browser, stringResource(error.reason.text())),
        Modifier.testTag("chromium-bridge-error"),
    ) {
        HbButton(
            stringResource(Res.string.chromium_bridge_dismiss),
            { onIntent(ChromiumBridgeScreenIntent.DismissError) },
            style = HbButtonStyle.Secondary,
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun ConnectionSection(state: ChromiumBridgeScreenState) {
    HbSettingsSection(stringResource(Res.string.chromium_bridge_section_connection)) {
        HbSettingsRow(
            stringResource(Res.string.chromium_bridge_server),
            Modifier.fillMaxWidth().testTag("chromium-bridge-server"),
            description = stringResource(state.server.text()),
        )
        if (state.connections.isEmpty()) {
            HbSettingsRow(stringResource(Res.string.chromium_bridge_no_connections), Modifier.fillMaxWidth())
        }
        state.connections.forEach { connection -> key(connection.id) { ConnectionRowItem(connection) } }
    }
}

@Composable
private fun ConnectionRowItem(connection: ConnectionRow) {
    val description = if (connection.isAgentControlAllowed) {
        Res.string.chromium_bridge_connection_agent
    } else {
        Res.string.chromium_bridge_connection
    }
    HbSettingsRow(
        stringResource(Res.string.chromium_bridge_connection_title, connection.brand, connection.version),
        Modifier.fillMaxWidth().testTag("chromium-bridge-connection-${connection.id}"),
        description = stringResource(description, connection.extensionVersion),
    )
}

@Composable
private fun BrowsersSection(state: ChromiumBridgeScreenState, onIntent: (ChromiumBridgeScreenIntent) -> Unit) {
    HbSettingsSection(
        stringResource(Res.string.chromium_bridge_section_browsers),
        description = stringResource(Res.string.chromium_bridge_browsers_hint),
        trailingContent = {
            HbButton(
                stringResource(Res.string.chromium_bridge_rescan),
                { onIntent(ChromiumBridgeScreenIntent.Rescan) },
                Modifier.testTag("chromium-bridge-rescan"),
                style = HbButtonStyle.Secondary,
                size = HbButtonSize.Small,
            )
        },
    ) {
        if (state.browsers.isEmpty()) {
            HbSettingsRow(stringResource(Res.string.chromium_bridge_no_browsers), Modifier.fillMaxWidth())
        }
        state.browsers.forEach { row ->
            key(row.browser) { BrowserRowItem(row, isLocked = state.busy != null, onIntent) }
        }
    }
}

@Composable
private fun BrowserRowItem(row: BrowserRow, isLocked: Boolean, onIntent: (ChromiumBridgeScreenIntent) -> Unit) {
    val name = stringResource(row.browser.title())
    val registration = stringResource(row.registration.text())
    val isRegistered = row.registration == RegistrationUi.Current
    val action = if (isRegistered) Res.string.chromium_bridge_unregister else Res.string.chromium_bridge_register
    val actionDescription = stringResource(
        if (isRegistered) {
            Res.string.chromium_bridge_unregister_description
        } else {
            Res.string.chromium_bridge_register_description
        },
        name,
    )
    HbSettingsRow(
        name,
        Modifier.fillMaxWidth().testTag("chromium-bridge-browser-${row.browser.name}"),
        description = if (row.isDetected) {
            registration
        } else {
            stringResource(Res.string.chromium_bridge_registration_missing, registration)
        },
    ) {
        HbButton(
            stringResource(action),
            {
                onIntent(
                    if (isRegistered) {
                        ChromiumBridgeScreenIntent.Unregister(row.browser)
                    } else {
                        ChromiumBridgeScreenIntent.Register(row.browser)
                    },
                )
            },
            Modifier.testTag("chromium-bridge-action-${row.browser.name}")
                .semantics { contentDescription = actionDescription },
            style = if (isRegistered) HbButtonStyle.Secondary else HbButtonStyle.Primary,
            enabled = !isLocked && (row.isDetected || isRegistered),
            size = HbButtonSize.Small,
        )
    }
}

@Composable
private fun ExtensionSection(state: ChromiumBridgeScreenState, onIntent: (ChromiumBridgeScreenIntent) -> Unit) {
    HbSettingsSection(
        stringResource(Res.string.chromium_bridge_section_extension),
        description = stringResource(Res.string.chromium_bridge_extension_hint),
    ) {
        HbSettingsRow(
            stringResource(Res.string.chromium_bridge_extension_folder),
            Modifier.fillMaxWidth().testTag("chromium-bridge-extension-folder"),
            description = state.extensionFolder ?: stringResource(Res.string.chromium_bridge_extension_folder_missing),
        ) {
            HbButton(
                stringResource(Res.string.chromium_bridge_reveal),
                { onIntent(ChromiumBridgeScreenIntent.RevealExtension) },
                Modifier.testTag("chromium-bridge-reveal"),
                style = HbButtonStyle.Secondary,
                enabled = state.extensionFolder != null,
                size = HbButtonSize.Small,
            )
        }
        HbSettingsRow(
            stringResource(Res.string.chromium_bridge_extension_id),
            Modifier.fillMaxWidth().testTag("chromium-bridge-extension-id"),
            description = state.extensionId,
        )
    }
}

@Composable
private fun AgentsSection(state: ChromiumBridgeScreenState, onIntent: (ChromiumBridgeScreenIntent) -> Unit) {
    HbSettingsSection(stringResource(Res.string.chromium_bridge_section_agents)) {
        HbSettingsRow(
            stringResource(Res.string.chromium_bridge_controlled_tabs),
            Modifier.fillMaxWidth().testTag("chromium-bridge-agent-tabs"),
            description = stringResource(Res.string.chromium_bridge_controlled_tabs_count, state.controlledTabs),
        ) {
            HbButton(
                stringResource(Res.string.chromium_bridge_revoke),
                { onIntent(ChromiumBridgeScreenIntent.RevokeTabs) },
                Modifier.testTag("chromium-bridge-revoke"),
                style = HbButtonStyle.Secondary,
                enabled = state.controlledTabs > 0 || state.connections.any { it.isAgentControlAllowed },
                size = HbButtonSize.Small,
            )
        }
    }
}

private fun BrowserUi.title(): StringResource = when (this) {
    BrowserUi.Chrome -> Res.string.chromium_bridge_browser_chrome
    BrowserUi.Edge -> Res.string.chromium_bridge_browser_edge
    BrowserUi.Brave -> Res.string.chromium_bridge_browser_brave
    BrowserUi.Chromium -> Res.string.chromium_bridge_browser_chromium
    BrowserUi.Vivaldi -> Res.string.chromium_bridge_browser_vivaldi
}

private fun RegistrationUi.text(): StringResource = when (this) {
    RegistrationUi.NotRegistered -> Res.string.chromium_bridge_registration_none
    RegistrationUi.Current -> Res.string.chromium_bridge_registration_current
    RegistrationUi.OtherBuild -> Res.string.chromium_bridge_registration_other
}

private fun ServerUi.text(): StringResource = when (this) {
    ServerUi.Starting -> Res.string.chromium_bridge_server_starting
    ServerUi.Listening -> Res.string.chromium_bridge_server_listening
    ServerUi.Occupied -> Res.string.chromium_bridge_server_occupied
    ServerUi.Failed -> Res.string.chromium_bridge_server_failed
}

private fun FailureUi.text(): StringResource = when (this) {
    FailureUi.UnsupportedPlatform -> Res.string.chromium_bridge_failure_platform
    FailureUi.Translocated -> Res.string.chromium_bridge_failure_translocated
    FailureUi.NoLauncher -> Res.string.chromium_bridge_failure_launcher
    FailureUi.WriteFailed -> Res.string.chromium_bridge_failure_write
}

private val previewState = ChromiumBridgeScreenState(
    availability = AvailabilityUi.Enabled,
    server = ServerUi.Listening,
    browsers = persistentListOf(
        BrowserRow(BrowserUi.Chrome, isDetected = true, RegistrationUi.Current),
        BrowserRow(BrowserUi.Edge, isDetected = true, RegistrationUi.NotRegistered),
        BrowserRow(BrowserUi.Brave, isDetected = false, RegistrationUi.OtherBuild),
    ),
    connections = persistentListOf(ConnectionRow(1, "Google Chrome", "140", "0.1.0", isAgentControlAllowed = true)),
    isAgentControlEnabled = true,
    controlledTabs = 2,
    extensionFolder = "/Applications/Heartbeat.app/Contents/app/resources/chromium-plugin",
    error = RegistrationError(BrowserUi.Edge, FailureUi.WriteFailed),
)

@Preview
@Composable
private fun ChromiumBridgeLightPreview() {
    HbTheme(darkTheme = false) { ChromiumBridgeContent(previewState, {}, null) }
}

@Preview
@Composable
private fun ChromiumBridgeDarkPreview() {
    HbTheme(darkTheme = true) { ChromiumBridgeContent(previewState, {}, {}) }
}

@Preview
@Composable
private fun ChromiumBridgeDisabledPreview() {
    HbTheme(darkTheme = false) {
        ChromiumBridgeContent(ChromiumBridgeScreenState(availability = AvailabilityUi.Disabled), {}, null)
    }
}
