package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.CompatibilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallSourceUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallSupportUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.InstallationUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.JobUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.KeyValueUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LaunchDraftUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LaunchOptionUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LaunchUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LoginMethodUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.LoginUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.RuntimeUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** Codex installed by Heartbeat with an update, a stale runtime and edited launch settings. */
private val PreviewPanel = EnginePanelUi(
    id = "codex",
    title = "Codex",
    isEnabled = true,
    isUserEnabled = true,
    isSwitchable = true,
    reasons = persistentListOf(),
    installation = InstallationUi(
        support = InstallSupportUi.Managed,
        source = InstallSourceUi.Managed,
        version = "0.160.0",
        path = "/Users/me/Library/Application Support/Heartbeat/engines/managed/codex/versions/0.160.0/bin/codex",
        managedVersion = "0.160.0",
        latest = "0.161.0",
        isUpdateAvailable = true,
        compatibility = CompatibilityUi.Unknown,
        isChecked = true,
    ),
    login = LoginUi.SignedIn("me@example.com"),
    loginMethods = persistentListOf(LoginMethodUi.Browser, LoginMethodUi.DeviceCode),
    launch = LaunchUi(
        options = persistentSetOf(LaunchOptionUi.Executable, LaunchOptionUi.HomeDirectory, LaunchOptionUi.Environment),
        homeVariable = "CODEX_HOME",
        draft = LaunchDraftUi(
            homeDirectory = "/Users/me/.codex-work",
            environment = persistentListOf(KeyValueUi("HTTPS_PROXY", "http://proxy:3128")),
        ),
        isEditing = true,
        isDirty = true,
        isCustomized = false,
        errors = persistentListOf(),
        warnings = persistentListOf(),
    ),
    runtime = RuntimeUi(runtimes = 1, openSessions = 2, isStale = true),
    job = JobUi(EngineActionUi.Update, JobPhaseUi.Downloading(bytes = 52_428_800, total = 129_976_298)),
    actions = persistentSetOf(EngineActionKindUi.Inspect, EngineActionKindUi.Configure),
)

@Preview
@Composable
private fun EngineManagementLightPreview() {
    HbTheme(darkTheme = false) { EngineManagementSection(PreviewPanel, "", isSaving = false, onIntent = {}) }
}

@Preview
@Composable
private fun EngineManagementDarkPreview() {
    HbTheme(darkTheme = true) { EngineManagementSection(PreviewPanel, "", isSaving = false, onIntent = {}) }
}

@Preview(widthDp = 360)
@Composable
private fun EngineManagementPhonePreview() {
    val signingIn = PreviewPanel.copy(
        login = LoginUi.SignedOut,
        job = JobUi(EngineActionUi.Login, JobPhaseUi.AwaitingCode("https://claude.ai/oauth/authorize")),
    )
    HbTheme(darkTheme = false) { EngineManagementSection(signingIn, "", isSaving = false, onIntent = {}) }
}
