package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.CompatibilityUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionKindUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineActionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EnginePanelUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.HostAppUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.HostRuntimeUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.InstallSourceUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.InstallSupportUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.InstallationUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.JobPhaseUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.JobUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.KeyValueUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchDraftUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchOptionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LaunchUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LoginMethodUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.LoginUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.RuntimePreferenceUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.RuntimeUi
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

/** Claude Code on the Claude app's runtime, with a newer runtime the app is still installing. */
private val PreviewHostAppPanel = PreviewPanel.copy(
    id = "claude",
    title = "Claude Code",
    installation = InstallationUi(
        support = InstallSupportUi.Managed,
        source = InstallSourceUi.HostApp,
        version = "2.1.304",
        isChecked = true,
    ),
    job = null,
    launch = PreviewPanel.launch?.copy(
        options = persistentSetOf(LaunchOptionUi.Executable, LaunchOptionUi.Runtime, LaunchOptionUi.HomeDirectory),
        homeVariable = "CLAUDE_CONFIG_DIR",
        draft = LaunchDraftUi(runtime = RuntimePreferenceUi.HostApp, hostRuntimeVersion = "2.1.304"),
        runtimes = persistentSetOf(
            RuntimePreferenceUi.Managed,
            RuntimePreferenceUi.HostApp,
            RuntimePreferenceUi.System,
        ),
        hostApp = "Claude",
        hostVersions = persistentListOf("2.1.304", "2.1.299"),
    ),
    actions = persistentSetOf(EngineActionKindUi.Inspect, EngineActionKindUi.Configure, EngineActionKindUi.OpenHostApp),
    hostApp = HostAppUi(
        title = "Claude",
        isChecked = true,
        isInstalled = true,
        version = "3.1623.1",
        path = "/Applications/Claude.app",
        runtimes = persistentListOf(
            HostRuntimeUi("2.1.305", "7c1d", isVerified = false),
            HostRuntimeUi("2.1.304", "6bbb", isVerified = true),
            HostRuntimeUi("2.1.299", "4fa9", isVerified = true),
        ),
        configDirectory = "/Users/me/.claude",
        isInUse = true,
    ),
)

@Preview
@Composable
private fun EngineHostAppLightPreview() {
    HbTheme(darkTheme = false) { EngineManagementSection(PreviewHostAppPanel, "", isSaving = false, onIntent = {}) }
}

@Preview
@Composable
private fun EngineHostAppDarkPreview() {
    HbTheme(darkTheme = true) { EngineManagementSection(PreviewHostAppPanel, "", isSaving = false, onIntent = {}) }
}
