package io.aequicor.heartbeat.feature.aistudio.impl.ui

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ApprovalUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EffortUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.EnvironmentUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.MessageUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.PaneUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ProjectUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ReplyPartUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SettingsUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.StudioPhase
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolStatusUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ToolUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Fixed data shared by before/after renders; no runtime, credentials or network are involved. */
internal fun desktopAuditWorkspace(isEmpty: Boolean): AiStudioScreenState {
    val time = Instant.parse("2026-09-28T09:20:00Z")
    val messages = (0..5).flatMap { index ->
        listOf(
            MessageUi.Prompt("audit-prompt-$index", time, "Проверь интерфейс и предложи улучшения: шаг ${index + 1}"),
            desktopAuditReply(index, time),
        )
    }.toImmutableList()
    return AiStudioScreenState(
        phase = StudioPhase.Ready,
        now = time,
        models = persistentListOf(
            ModelUi(
                "audit-model",
                "Koog · Qwen3 Plus · Рабочее подключение",
                isResearchSupported = true,
                isLocalProjectSupported = true,
                shortName = "Qwen3 Plus",
                reasoningEfforts = persistentListOf("low", "medium", "high"),
                defaultReasoningEffort = "medium",
            ),
        ),
        settings = SettingsUi("audit-model", EffortUi.High, ApprovalUi.Ask),
        isResearchEnabled = true,
        projects = persistentListOf(ProjectUi("audit-project", "Heartbeat / Дизайн", EnvironmentUi.Local, "master")),
        sessions = (0 until 50).map { index ->
            SessionUi(
                id = "audit-$index",
                title = if (index == 0) {
                    "Длинное название выбранного чата: проверка доступности и русских подписей"
                } else {
                    "Чат ${index + 1}: ${desktopAuditTitles[index % desktopAuditTitles.size]}"
                },
                projectId = "audit-project".takeIf { index in 2..7 },
                updatedAt = time - index.minutes,
                isPinned = index < 2,
                isUnread = index % 7 == 0,
            )
        }.toImmutableList(),
        panes = persistentListOf(PaneUi(0, sessionId = "audit-0".takeUnless { isEmpty })),
        transcripts = persistentMapOf("audit-0" to messages),
    )
}

private fun desktopAuditReply(index: Int, time: Instant): MessageUi.Reply {
    val tools = persistentListOf(
        ToolUi("audit-files-$index", "Чтение файлов", ToolStatusUi.Done, "StudioSidebar.kt\nSessionRows.kt", null),
        ToolUi(
            "audit-command-$index",
            "Проверка сборки — шаг ${index + 1}",
            ToolStatusUi.Done,
            "BUILD SUCCESSFUL\n18 tests completed, 0 failed\nDesktop layout: 1280 × 800",
            null,
        ),
    )
    val intro = "Проверил навигацию, доступность действий с клавиатуры и длинные русские заголовки."
    val conclusion = """
        ### Результат проверки

        Текст, код и результаты инструментов остаются в одном ответе. Навигация сохраняет контекст разговора.

        ```kotlin
        fun openConversation(id: String) {
            dispatch(OpenSession(id))
        }
        ```

        - Сохранить выбранный чат при изменении ширины.
        - Сделать поиск и действия заметными без лишнего шума.
        - Проверить **фокус клавиатуры** и `очень_длинные_идентификаторы_без_пробелов`.
    """.trimIndent()
    return MessageUi.Reply(
        id = "audit-answer-$index",
        createdAt = time,
        text = "$intro\n\n$conclusion",
        tools = tools,
        isStreaming = false,
        parts = persistentListOf(
            ReplyPartUi.Text("audit-intro-$index", intro),
            ReplyPartUi.Reasoning("audit-thinking-$index", "Сравниваю плотность навигации и ширину колонки ответа."),
            ReplyPartUi.Tool(tools[0]),
            ReplyPartUi.Tool(tools[1]),
            ReplyPartUi.Text("audit-conclusion-$index", conclusion),
        ),
    )
}

private val desktopAuditTitles = listOf(
    "Проверка интерфейса и поведения навигации",
    "План следующего релиза",
    "Работа с длинными русскими названиями",
    "Ревью архитектуры проекта",
    "Тестирование потоковых ответов",
)
