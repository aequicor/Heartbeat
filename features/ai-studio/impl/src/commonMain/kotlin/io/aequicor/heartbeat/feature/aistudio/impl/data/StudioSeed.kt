package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioMessage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioProject
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioToolRun
import io.aequicor.heartbeat.feature.aistudio.impl.domain.ToolRunStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val HEARTBEAT = "p-heartbeat"
private const val SITE = "p-site"

/** The project the studio opens with. */
internal const val DEFAULT_PROJECT_ID = HEARTBEAT

/** Demo workspace shown on the first start: two projects and a handful of realistic sessions. */
internal fun studioSeed(now: Instant): StudioData {
    val seed = SeedBuilder(now)
    seed.session("s-build", null, "Найти способы ускорить сборку", 9.minutes, isUnread = true) {
        prompt(SeedContent.BUILD_PROMPT)
        reply(SeedContent.BuildReply, tool("Проверил настройки Gradle", SeedContent.BuildConsole))
    }
    seed.session("s-facade", HEARTBEAT, "Спроектировать фасад AI-движков", 14.minutes) {
        prompt(SeedContent.FACADE_PROMPT)
        reply(SeedContent.FacadeReply, tool("Изучил модули core", SeedContent.FacadeConsole))
        prompt("Как передавать сессию между движками?")
        reply(SeedContent.HandoffReply)
    }
    seed.session("s-handoff", HEARTBEAT, "Передача сессии между движками", 26.minutes, isUnread = true) {
        prompt(SeedContent.TRANSFER_PROMPT)
        reply(SeedContent.TransferReply)
    }
    seed.session("s-adr", HEARTBEAT, "Провести ревью аналитики ADR", 48.minutes) {
        prompt("Проведи ревью аналитики ADR по хранению секретов профиля.")
        reply(SeedContent.AdrReply, tool("Прочитал правила и KDoc", SeedContent.AdrConsole))
    }
    seed.session("s-core-ai", HEARTBEAT, "Реализовать модуль core:ai", 2.hours, branch = "feature/core-ai") {
        prompt("Добавь модуль core:ai с провайдерами LLM по нашим конвенциям.")
        reply(SeedContent.CoreAiReply, tool("Внёс изменения", SeedContent.CoreAiConsole, SeedContent.CoreAiDiff))
    }
    seed.session("s-landing", SITE, "Обновить лендинг под релиз 0.3", 3.hours, branch = "release/landing-0.3") {
        prompt("Обнови лендинг под релиз 0.3: новые скриншоты студии и раздел про тоглы.")
        reply(SeedContent.LANDING_REPLY)
    }
    seed.session("s-budget", HEARTBEAT, "Ограничить размер PR", 20.hours, branch = "chore/commit-budget") {
        prompt("Сделай проверку размера коммитов по токенам diff, чтобы ревью оставалось посильным.")
        reply(SeedContent.BudgetReply, tool("Выполнил команды", SeedContent.BudgetConsole))
    }
    seed.session("s-greeting", null, "Приветствие", 2.days) {
        prompt("Привет! Что ты умеешь?")
        reply(SeedContent.GreetingReply)
    }
    seed.session("s-prototype", HEARTBEAT, "Прототип чата на Material", 5.days, isArchived = true) {
        prompt("Собери быстрый прототип чата на Material 3.")
        reply("Прототип устарел: экран перенесён на компоненты дизайн-системы Glass.")
    }
    return StudioData(
        projects = listOf(
            StudioProject(HEARTBEAT, "heartbeat", StudioEnvironment.Local, "master"),
            StudioProject(SITE, "aequicor-site", StudioEnvironment.Cloud, "main"),
        ),
        sessions = seed.sessions,
        messages = seed.messages,
    )
}

private class SeedBuilder(private val now: Instant) {
    val sessions = mutableListOf<StudioSession>()
    val messages = mutableMapOf<String, List<StudioMessage>>()

    fun session(
        id: String,
        projectId: String?,
        title: String,
        age: Duration,
        isUnread: Boolean = false,
        isArchived: Boolean = false,
        branch: String? = null,
        transcript: TranscriptBuilder.() -> Unit,
    ) {
        val start = now - age
        sessions += StudioSession(
            id = id,
            projectId = projectId,
            title = title,
            updatedAt = start,
            isUnread = isUnread,
            isArchived = isArchived,
            branch = branch,
        )
        messages[id] = TranscriptBuilder(id, start).apply(transcript).entries
    }
}

private class TranscriptBuilder(private val sessionId: String, private var time: Instant) {
    val entries = mutableListOf<StudioMessage>()

    fun prompt(text: String) {
        entries += StudioMessage.Prompt(nextId(), next(), text)
    }

    fun reply(text: String, vararg tools: StudioToolRun) {
        entries += StudioMessage.Reply(nextId(), next(), text, tools.toList())
    }

    fun tool(title: String, output: String, diff: String? = null): StudioToolRun =
        StudioToolRun("$sessionId-tool-${entries.size}", title, ToolRunStatus.Done, output, diff)

    private fun nextId() = "$sessionId-${entries.size}"

    private fun next(): Instant = time.also { time += 1.minutes }
}
