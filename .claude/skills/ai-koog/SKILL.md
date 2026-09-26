---
name: ai-koog
description: "ИИ-функциональность Heartbeat на Koog (core:ai) — провайдеры LLM и PromptExecutor, агенты AIAgent, инструменты (Tool/SimpleTool, ToolSet, ToolRegistry), стриминг, логирование через handleEvents, безопасное хранение API-ключей, тестирование без сети. Используй при добавлении/изменении агента, инструмента, модели или провайдера."
---

# ИИ (Koog)

Документация: https://docs.koog.ai/ . Артефакт `ai.koog:koog-agents` (варианты android/jvm/ios; JVM требует JDK 17+). Версия — [tech-stack.md](../../../docs/ai/tech-stack.md).

## Слои

```
core:ai            LlmProviderRegistry, PromptExecutor по провайдеру, AgentFactory (ставит логирование),
                   SecretStore для ключей, базовые инструменты, модели Ui-независимые
features/*/impl    конкретные агенты и инструменты фичи; вызываются из Effects машины
```

Фича не создаёт `simpleXxxExecutor` сама — получает `PromptExecutor`/`AgentFactory` из `core:ai` через DI.

## Провайдеры и ключи

```kotlin
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultLlmProviderRegistry(private val secrets: SecretStore) : LlmProviderRegistry {
    override suspend fun executor(provider: LlmProvider): PromptExecutor = when (provider) {
        LlmProvider.Anthropic -> simpleAnthropicExecutor(secrets.require(SecretKey.Anthropic))
        LlmProvider.OpenAI -> simpleOpenAIExecutor(secrets.require(SecretKey.OpenAI))
        LlmProvider.Google -> simpleGoogleAIExecutor(secrets.require(SecretKey.Google))
        LlmProvider.OpenRouter -> simpleOpenRouterExecutor(secrets.require(SecretKey.OpenRouter))
        LlmProvider.Ollama -> simpleOllamaAIExecutor()               // локально, desktop
    }
}
```

- Ключи — только через `SecretStore` (Android Keystore / iOS Keychain / Windows DPAPI / macOS Keychain). Не в `DataStore` открытым текстом, не в `BuildConfig`, не в логах.
- Выбор провайдера/модели — настройка пользователя (DataStore) + тогл для экспериментальных моделей.

## Агент с инструментами

```kotlin
// Инструмент (class-based)
internal class SearchProjectFilesTool(private val files: ProjectFiles) : SimpleTool<SearchProjectFilesTool.Args>() {
    @Serializable
    data class Args(@property:LLMDescription("Substring to search in file names") val query: String)

    override val argsSerializer = Args.serializer()
    override val description = "Searches files of the current project by name"
    override suspend fun doExecute(args: Args): String = files.search(args.query).joinToString("\n")
}

// Агент
internal class ChatAgent @Inject constructor(private val factory: AgentFactory, private val files: ProjectFiles) {
    suspend fun reply(prompt: String, model: LLModel): String {
        val agent = factory.create(                                   // core:ai: AIAgent + логирующий handleEvents
            systemPrompt = "You are Heartbeat studio assistant.",
            model = model,
            tools = ToolRegistry { tool(SearchProjectFilesTool(files)) },
        )
        return agent.run(prompt)
    }
}
```

Annotation-based вариант: класс `ToolSet` с методами `@Tool @LLMDescription(...)` и `ToolRegistry { tools(myToolSet) }`.

## Логирование (обязательно, в `AgentFactory`)

```kotlin
AIAgent(promptExecutor = executor, llmModel = model, systemPrompt = systemPrompt, toolRegistry = tools) {
    handleEvents {
        onAgentStarting { log.i { "AI: agent start model=${model.id}" } }
        onLLMCallStarting { log.i { "AI: llm call" } }
        onLLMCallCompleted { log.i { "AI: llm done" } }
        onToolCallStarting { ctx -> log.i { "AI: tool ${ctx.tool.name}" } }
        onToolCallFailed { ctx -> log.w { "AI: tool ${ctx.tool.name} failed" } }
        onAgentExecutionFailed { ctx -> log.e(ctx.throwable) { "AI: agent failed" } }
        onAgentCompleted { log.i { "AI: agent completed" } }
    }
}
```

Точные поля контекстов событий сверяй с https://docs.koog.ai/ (`features/agent-event-handlers`). В release не логируй текст промптов/ответов — только длины, модель, токены, длительность.

## Стриминг

Для потокового ответа — Streaming API (`onLLMStreamingFrameReceived`, `StreamFrame.Append`) → эффект машины шлёт промежуточные `Internal.Chunk`-события или пишет в репозиторий, стор подписан на репозиторий. Потоковый UI — за тоглом.

## Интеграция с машиной

- Вызов агента — только из `<Name>EffectsImpl` в ответ на вход в состояние (`Generating`), результат — событие `Internal.Completed/Failed`.
- Отмена — `Job.cancel()` эффекта при событии `Cancel`; `CancellationException` не глотать.
- Таймауты и ретраи — в `core:ai`, не в фиче.

## Тесты

Мок `PromptExecutor` (или тестовые утилиты Koog, см. docs «Testing») с заранее заданными ответами/tool-calls; никаких реальных сетевых вызовов. Проверяй: вызван ли нужный инструмент, как обрабатывается ошибка провайдера, что уходит в машину.
