package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFieldUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigFormUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigPageUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigProblemReasonUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigProblemUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.ConfigSectionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EntryUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FieldEditorUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.KeyValueRowUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.OptionUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.SecretUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextRoleUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.TextUi
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.UnitUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

private fun text(en: String, ru: String) = TextUi(en, persistentMapOf("ru" to ru))

/** A Pi-like network page with every basic editor, an edited value with a problem and a managed key. */
private val PreviewPage = ConfigPageUi(
    "network",
    text("Network and retries", "Сеть и повторы"),
    persistentListOf(
        ConfigSectionUi(
            "transport",
            text("Transport", "Транспорт"),
            text("Heartbeat starts Pi for every chat.", "Heartbeat запускает Pi для каждого чата."),
            persistentListOf(
                ConfigFieldUi(
                    "transport",
                    text("Transport", "Транспорт"),
                    null,
                    FieldEditorUi.Choice(
                        persistentListOf(
                            OptionUi("\"sse\"", text("Server-sent events", "Server-sent events")),
                            OptionUi("\"websocket\"", text("WebSocket", "WebSocket")),
                        ),
                        selected = "\"websocket\"",
                        default = "\"sse\"",
                    ),
                    isSet = true,
                ),
                ConfigFieldUi(
                    "offline",
                    text("Offline", "Без сети"),
                    text("Skip update checks and downloads.", "Не проверять обновления и не скачивать."),
                    FieldEditorUi.Flag(isOn = null, isOnByDefault = false),
                    isSet = false,
                ),
                ConfigFieldUi(
                    "timeout",
                    text("Request timeout", "Таймаут запроса"),
                    null,
                    FieldEditorUi.Number("9oo", default = 120, min = 1, max = 3_600, unit = UnitUi.Seconds),
                    isSet = true,
                    problems = persistentListOf(ConfigProblemUi(ConfigProblemReasonUi.NotANumber)),
                ),
                ConfigFieldUi(
                    "proxy",
                    text("HTTP proxy", "HTTP-прокси"),
                    null,
                    FieldEditorUi.Text("", TextRoleUi.Url, default = null),
                    isSet = false,
                ),
                ConfigFieldUi(
                    "skills",
                    text("Skills", "Навыки"),
                    null,
                    FieldEditorUi.TextList(
                        persistentListOf("skills", "!skills/old"),
                        TextRoleUi.Path,
                        isPatterns = true,
                    ),
                    isSet = true,
                ),
                ConfigFieldUi(
                    "model",
                    text("Default model", "Модель по умолчанию"),
                    null,
                    FieldEditorUi.Managed(text("Chosen in the chat.", "Выбирается в чате.")),
                    isSet = false,
                ),
                ConfigFieldUi(
                    "servers",
                    text("Tool servers", "Серверы инструментов"),
                    null,
                    FieldEditorUi.Entries(
                        text("Server", "Сервер"),
                        isNamed = true,
                        variants = persistentListOf(OptionUi("stdio", text("Command", "Команда"))),
                        items = persistentListOf(
                            EntryUi(
                                "github",
                                "stdio",
                                persistentListOf(
                                    ConfigFieldUi(
                                        "command",
                                        text("Command", "Команда"),
                                        null,
                                        FieldEditorUi.Text("npx", TextRoleUi.Command, default = null),
                                        isSet = true,
                                    ),
                                    ConfigFieldUi(
                                        "env",
                                        text("Environment", "Окружение"),
                                        null,
                                        FieldEditorUi.KeyValues(
                                            persistentListOf(
                                                KeyValueRowUi("GITHUB_TOKEN", slot = "GITHUB", secret = SecretUi.Saved),
                                                KeyValueRowUi("LOG_LEVEL", "debug"),
                                            ),
                                            isSecretAllowed = true,
                                            valueChoices = null,
                                        ),
                                        isSet = true,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    isSet = true,
                ),
            ),
        ),
    ),
)

private val PreviewForm = ConfigFormUi(persistentListOf(PreviewPage), isDirty = true, blockingProblems = 1)

@Preview
@Composable
private fun ConfigPageLightPreview() {
    HbTheme(darkTheme = false) { ConfigPageContent(PreviewPage, PreviewForm, isSaving = false, onIntent = {}) }
}

@Preview
@Composable
private fun ConfigPageDarkPreview() {
    HbTheme(darkTheme = true) { ConfigPageContent(PreviewPage, PreviewForm, isSaving = false, onIntent = {}) }
}

@Preview(widthDp = 360)
@Composable
private fun ConfigPageConflictPreview() {
    HbTheme(darkTheme = false) {
        ConfigPageContent(PreviewPage, PreviewForm.copy(hasConflict = true), isSaving = false, onIntent = {})
    }
}
