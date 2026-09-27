package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.ApprovalMode
import io.aequicor.heartbeat.feature.aistudio.api.ReasoningEffort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AgentEvent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AgentRequest
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAgent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Offline stand-in for an LLM agent until `core:ai` provides real providers. Streams a deterministic plan
 * with tool calls shaped by the request: faster models answer sooner, higher effort explores more, and
 * [ApprovalMode.AutoApprove] lets the agent edit files and push a branch. Content is demo Russian copy.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class ScriptedStudioAgent : StudioAgent {
    private val log = Log.tag("ScriptedStudioAgent")

    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val pace = pace(request)
        val topic = Topic.of(request.prompt)
        val project = request.project?.name ?: "рабочей папке"
        log.i { "scripted run effort=${request.settings.effort} approval=${request.settings.approval}" }
        stream("Изучаю задачу в $project. ", pace)
        if (request.settings.effort != ReasoningEffort.Low) {
            tool("explore", "Выполняется поиск по проекту", "Изучил проект", topic.exploration, pace)
        }
        if (request.settings.effort == ReasoningEffort.VeryHigh) {
            tool("verify", "Выполняется проверка сборки", "Проверил сборку", VERIFY_CONSOLE, pace)
        }
        val isEditing = request.settings.approval == ApprovalMode.AutoApprove && topic.diff != null
        if (isEditing) {
            tool("edit", "Выполняется правка файлов", "Внёс изменения", EDIT_CONSOLE, pace, topic.diff)
            emit(AgentEvent.BranchCreated("studio/${topic.branch}"))
        }
        stream(topic.answer, pace)
        val footer = when {
            isEditing -> "\n\nИзменения сохранены в ветке `studio/${topic.branch}`."
            topic.diff != null -> READ_ONLY_FOOTER
            else -> ""
        }
        stream(footer, pace)
    }

    private suspend fun FlowCollector<AgentEvent>.stream(text: String, pace: Long) {
        text.chunked(CHUNK).forEach { chunk ->
            delay(pace)
            emit(AgentEvent.Text(chunk))
        }
    }

    @Suppress("LongParameterList") // Mirrors the fields of one scripted tool call.
    private suspend fun FlowCollector<AgentEvent>.tool(
        id: String,
        runningTitle: String,
        doneTitle: String,
        output: String,
        pace: Long,
        diff: String? = null,
    ) {
        emit(AgentEvent.ToolStarted(id, runningTitle))
        output.lines().forEach { line ->
            delay(pace * TOOL_LINE_FACTOR)
            emit(AgentEvent.ToolOutput(id, line + "\n"))
        }
        emit(AgentEvent.ToolFinished(id, doneTitle, isSuccess = true, diff = diff))
    }

    private fun pace(request: AgentRequest): Long = when (request.settings.modelId) {
        StudioModels.last().id -> FAST_MILLIS
        StudioModels.first().id -> CAREFUL_MILLIS
        else -> NORMAL_MILLIS
    }

    private enum class Topic(
        val keywords: List<String>,
        val exploration: String,
        val answer: String,
        val diff: String?,
        val branch: String,
    ) {
        Tests(listOf("тест", "test"), TESTS_CONSOLE, TESTS_ANSWER, TESTS_DIFF, "tests"),
        Review(listOf("ревью", "review", "pull request"), REVIEW_CONSOLE, REVIEW_ANSWER, null, "review"),
        Plan(emptyList(), PLAN_CONSOLE, PLAN_ANSWER, PLAN_DIFF, "plan"),
        ;

        companion object {
            fun of(prompt: String): Topic {
                val text = prompt.lowercase()
                return entries.firstOrNull { topic -> topic.keywords.any { it in text } } ?: Plan
            }
        }
    }

    private companion object {
        const val CHUNK = 14
        const val FAST_MILLIS = 12L
        const val NORMAL_MILLIS = 22L
        const val CAREFUL_MILLIS = 34L
        const val TOOL_LINE_FACTOR = 8
    }
}

private const val READ_ONLY_FOOTER = "\n\nФайлы не менялись: включите «Подтверждать за меня», " +
    "чтобы агент вносил правки сам."

private const val VERIFY_CONSOLE = """${'$'} ./gradlew :features:ai-studio:impl:jvmTest
BUILD SUCCESSFUL in 38s
${'$'} ./gradlew detekt
BUILD SUCCESSFUL in 21s"""

private const val EDIT_CONSOLE = """${'$'} git switch -c studio/change
Switched to a new branch 'studio/change'
${'$'} git commit -am "Apply studio changes"
[studio/change 4c1e9a2] Apply studio changes"""

private const val PLAN_CONSOLE = """${'$'} git status --short
${'$'} rg --files-with-matches "machineSpec" features
features/welcome/api/src/commonMain/kotlin/io/aequicor/heartbeat/feature/welcome/api/WelcomeContract.kt
features/ai-studio/api/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aistudio/api/AiStudioMachineSpec.kt"""

private const val PLAN_ANSWER = """Предлагаю план из трёх шагов:

1. **Контракт** — описать состояния и переходы в `api` через `machineSpec { }` и покрыть их тестами `assertTransition`.
2. **Данные** — репозиторий в `impl` с логированием записей, без утечки содержимого в логи.
3. **Экран** — стор FlowMVI, который отражает машину, и UI только на компонентах `Hb*`.

Каждый шаг — отдельный коммит до 25 000 токенов diff."""

private const val PLAN_DIFF = """diff --git a/features/ai-studio/api/src/commonMain/kotlin/Plan.kt b/features/ai-studio/api/src/commonMain/kotlin/Plan.kt
--- a/features/ai-studio/api/src/commonMain/kotlin/Plan.kt
+++ b/features/ai-studio/api/src/commonMain/kotlin/Plan.kt
@@ -1,3 +1,5 @@
 public sealed interface PlanState : MachineState {
     public data object Idle : PlanState
+    public data class Drafting(val steps: List<String>) : PlanState
+    public data object Approved : PlanState
 }"""

private const val TESTS_CONSOLE = """${'$'} ./gradlew :features:ai-studio:impl:jvmTest --tests "*Store*"
AiStudioModelTest > submit clears the draft PASSED
AiStudioModelTest > stop forwards the running session PASSED
BUILD SUCCESSFUL in 27s"""

private const val TESTS_ANSWER = """Покрытие стора хорошее, но не хватает сценариев:

- отказ машины (`Ignored`) при повторной отправке — черновик не должен теряться;
- восстановление черновика по `SubmitFailed`;
- таймер «Работает уже…» на виртуальном времени без `delay` в тестах."""

private const val TESTS_DIFF = """diff --git a/features/ai-studio/impl/src/commonTest/kotlin/AiStudioModelTest.kt b/features/ai-studio/impl/src/commonTest/kotlin/AiStudioModelTest.kt
--- a/features/ai-studio/impl/src/commonTest/kotlin/AiStudioModelTest.kt
+++ b/features/ai-studio/impl/src/commonTest/kotlin/AiStudioModelTest.kt
@@ -40,2 +40,8 @@
     }
+
+    @Test
+    fun `failed submit restores the draft`() = runTest {
+        machine.outputs.emit(AiStudioOutput.SubmitFailed(0, "Draft"))
+        assertEquals("Draft", model.store.states.value.drafts[0])
+    }
 }"""

private const val REVIEW_CONSOLE = """${'$'} git diff --stat origin/master...HEAD
 features/ai-studio/impl/src/commonMain/.../AiStudioScreen.kt | 212 +++++++++++-----
 features/ai-studio/impl/src/commonMain/.../AiStudioModel.kt  |  96 ++++++--
 2 files changed, 241 insertions(+), 67 deletions(-)"""

private const val REVIEW_ANSWER = """Ревью изменений:

- ✅ Машина остаётся единственным источником бизнес-флоу, стор только отражает её.
- ⚠️ `AiStudioScreen` превышает 200 строк — стоит вынести сайдбар в отдельный файл.
- ⚠️ Нет теста на закрытие панели, пока сессия выполняется.

Блокеров нет, можно мёржить после правок."""
