package io.aequicor.heartbeat.feature.aiengine.connections.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.ApiKeyMethod
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.AccessGroupUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.AvailabilityUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectWizardScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectionEditDraft
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ConnectionRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.CredentialForm
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineConnectionsScreenState
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EnginePreparationUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.EngineRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodKindUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.MethodRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelRowUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.ModelsPaneUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.PreparationPhaseUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.RuntimeChoiceUi
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.WizardStep
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.checkedSettingsSnapshot
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.openCheckDetails
import io.aequicor.heartbeat.feature.aiengine.connections.impl.presentation.store.reflect
import io.aequicor.heartbeat.feature.aiengine.facade.api.scopeFor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Renders the settings space and the wizard at desktop and compact widths into `build/reports/snapshots`. */
@OptIn(ExperimentalTestApi::class)
class ConnectionsSnapshotTest {
    private val models = persistentListOf(
        ModelRowUi("gpt-5", "GPT-5", 400_000, isEnabled = true, isDefault = true),
        ModelRowUi("gpt-5-mini", "GPT-5 mini", 400_000, isEnabled = true),
        ModelRowUi("o4-reasoning-extended-preview", "o4 reasoning extended preview", null, isEnabled = false),
    )
    private val connections = EngineConnectionsScreenState(
        engines = persistentListOf(
            EngineRowUi("koog", "Koog", AvailabilityUi.Available, 1, isConnectable = true),
            EngineRowUi("codex", "Codex", AvailabilityUi.Unavailable, 0, isConnectable = true),
        ),
        selectedEngine = "koog",
        connections = persistentListOf(
            ConnectionRowUi("c1", "Рабочий OpenAI", "OpenAI", MethodKindUi.ApiKey, "https://api.openai.com", true, 2),
        ),
        selectedConnection = "c1",
        models = ModelsPaneUi(models, isStale = false, isNeverSynced = false),
        isLoading = false,
    )
    private val wizard = ConnectWizardScreenState(
        step = WizardStep.Method,
        engineTitle = "Koog",
        methods = persistentListOf(
            MethodRowUi("openai", "OpenAI", MethodKindUi.ApiKey, "https://api.openai.com", false, false),
            MethodRowUi("ollama", "Ollama", MethodKindUi.NoAuth, "http://localhost:11434", true, false),
        ),
        selectedMethod = "openai",
        selectedGroup = AccessGroupUi.Provider,
        isBusy = false,
    )

    @Test
    fun `renders snapshots`() {
        for (width in listOf(1280, 900, 420)) {
            for (isDark in listOf(false, true)) {
                val checked = EngineConnectionsScreenState().reflect(
                    EngineConnectionsState.Active(checkedSettingsSnapshot()),
                )
                val target = requireNotNull(checked.models?.models?.first()?.check).target
                render("check-warning", width, isDark) {
                    EngineConnectionsContent(checked, {}, {}, null, { _, _ -> })
                }
                render("check-details", width, isDark) {
                    EngineConnectionsContent(checked.openCheckDetails(target), {}, {}, null, { _, _ -> })
                }
                render("connections", width, isDark) { EngineConnectionsContent(connections, {}, {}, {}, { _, _ -> }) }
                val source = AuthSource.ManagedKey(
                    AuthSourceInfo(AuthSourceId("source"), "Рабочий OpenAI", AuthRevision.Known("r1")),
                    ApiKeyMethod.scopeFor(),
                    AuthSecretId("secret"),
                )
                val editing = connections.copy(
                    editDraft = ConnectionEditDraft(
                        "c1",
                        source,
                        ApiKeyMethod,
                        CredentialForm("Рабочий OpenAI", ApiKeyMethod.origin.value),
                    ),
                )
                render("connection-edit", width, isDark) {
                    EngineConnectionsContent(editing, {}, {}, null, { _, _ -> })
                }
                render("connection-edit-saving", width, isDark) {
                    EngineConnectionsContent(editing.copy(isSaving = true), {}, {}, null, { _, _ -> })
                }
                render("connection-edit-conflict", width, isDark) {
                    EngineConnectionsContent(
                        editing.copy(failure = FailureUi.ConnectionChanged, isEditFailure = true),
                        {},
                        {},
                        null,
                        { _, _ -> },
                    )
                }
                renderWizard(width, isDark)
            }
        }
    }

    private fun renderRuntime(width: Int, isDark: Boolean) {
        val runtime = wizard.copy(step = WizardStep.Runtime, engineTitle = "Pi", isBusy = false, hasRuntimeStep = true)
        render("wizard-runtime-local", width, isDark) { ConnectWizardContent(runtime, {}) }
        render("wizard-runtime-ready", width, isDark) {
            ConnectWizardContent(
                runtime.copy(
                    hasCheckedLocalRuntime = true,
                    hasRuntimeChoice = true,
                    isRuntimeUsable = true,
                    isRuntimePickerAvailable = true,
                    isSystemRuntimeAvailable = true,
                    selectedRuntimeChoice = RuntimeChoiceUi.System,
                    isManagedInstallationAvailable = true,
                ),
                {},
            )
        }
        render("wizard-runtime-unknown", width, isDark) {
            ConnectWizardContent(
                runtime.copy(
                    hasCheckedLocalRuntime = true,
                    hasRuntimeChoice = true,
                    isRuntimePickerAvailable = true,
                    isBundledRuntimeAvailable = true,
                    selectedRuntimeChoice = RuntimeChoiceUi.Bundled,
                    isManagedInstallationAvailable = true,
                ),
                {},
            )
        }
        render("wizard-runtime-download", width, isDark) {
            ConnectWizardContent(
                runtime.copy(
                    isBusy = true,
                    hasCheckedLocalRuntime = true,
                    runtimeJobProgress = EnginePreparationUi(PreparationPhaseUi.Downloading, 42),
                ),
                {},
            )
        }
    }

    private fun renderWizard(width: Int, isDark: Boolean) {
        renderRuntime(width, isDark)
        render("wizard", width, isDark) { ConnectWizardContent(wizard, {}) }
        render("wizard-access-groups", width, isDark) {
            val groups = wizard.methods + listOf(
                MethodRowUi(
                    "plan",
                    "Z.ai",
                    MethodKindUi.ApiKey,
                    "https://api.z.ai",
                    false,
                    false,
                    accessGroup = AccessGroupUi.Subscription,
                ),
                MethodRowUi(
                    "compatible",
                    "OpenAI",
                    MethodKindUi.ApiKey,
                    "http://localhost:8000",
                    true,
                    true,
                    accessGroup = AccessGroupUi.OpenAiCompatible,
                ),
                MethodRowUi(
                    "anthropic-compatible",
                    "Anthropic",
                    MethodKindUi.ApiKey,
                    "http://localhost:4000",
                    true,
                    true,
                    accessGroup = AccessGroupUi.AnthropicCompatible,
                ),
            )
            ConnectWizardContent(
                wizard.copy(methods = groups.toImmutableList(), selectedGroup = null, selectedMethod = null),
                {},
            )
        }
        render("wizard-access-providers", width, isDark) {
            ConnectWizardContent(wizard.copy(selectedMethod = null), {})
        }
        render("preparing", width, isDark) {
            ConnectWizardContent(
                wizard.copy(isBusy = true, preparation = EnginePreparationUi(PreparationPhaseUi.Downloading, 42)),
                {},
            )
        }
        render("preparation-failed", width, isDark) {
            ConnectWizardContent(wizard.copy(preparation = EnginePreparationUi(PreparationPhaseUi.Network)), {})
        }
    }

    private fun render(name: String, width: Int, isDark: Boolean, content: @Composable () -> Unit) =
        runSkikoComposeUiTest(size = Size(width.toFloat(), 800f)) {
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                ) { HbTheme(darkTheme = isDark) { content() } }
            }
            waitForIdle()
            val file = File("build/reports/snapshots/$name-$width-${if (isDark) "dark" else "light"}.png")
            file.parentFile.mkdirs()
            ImageIO.write(captureToImage().toAwtImage(), "png", file)
        }
}
