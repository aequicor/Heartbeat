---
name: network
description: "Сетевой слой Heartbeat на Ktor 3 (core:network) — HttpClient с engine per-platform, ContentNegotiation/JSON, таймауты, ретраи, логирование через Napier с редактированием секретов, API-клиенты фич, обработка ошибок, тесты через MockEngine. Используй при добавлении HTTP-запросов или изменении клиента."
---

# Сеть (Ktor)

Документация: https://ktor.io/docs/client-create-new-application.html . Версия — [tech-stack.md](../../../docs/ai/tech-stack.md).
LLM-провайдеры ходят через Koog (скилл `ai-koog`), а не через этот клиент.

## Клиент (`core:network`)

Engines: `ktor-client-okhttp` (androidMain, jvmMain), `ktor-client-darwin` (iosMain) — `expect fun platformEngine(): HttpClientEngine`.

```kotlin
internal fun createHttpClient(engine: HttpClientEngine, json: Json): HttpClient = HttpClient(engine) {
    expectSuccess = true
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 2)
        exponentialDelay()
    }
    install(Logging) {
        logger = object : Logger {
            private val log = Log.tag("NET")
            override fun log(message: String) = log.d { message }
        }
        level = if (BuildFlags.isDebug) LogLevel.HEADERS else LogLevel.INFO
        sanitizeHeader { it == HttpHeaders.Authorization || it.equals("x-api-key", true) || it.equals("cookie", true) }
    }
}
```

- Метод/URL/статус/длительность логируются на `I` — это требование [logging-policy](../../../docs/ai/logging-policy.md).
- Тело запросов/ответов (`LogLevel.BODY`) — никогда в release; в debug — только если не содержит пользовательских данных.

## API фичи (`impl/data`)

```kotlin
@Inject
internal class ProjectsApi(private val client: HttpClient, private val config: NetworkConfig) {
    suspend fun list(): List<ProjectDto> = client.get("${config.baseUrl}/projects").body()
}
```

- DTO — `@Serializable` в `impl/data`, маппинг в domain там же.
- Ошибки: `ResponseException`/`IOException` ловятся в репозитории, логируются и превращаются в доменную ошибку (`Result`/sealed) → событие `Internal.Failed` машины. `CancellationException` пробрасывается.
- Base URL и окружения — `NetworkConfig` из DI (не хардкод).

## Тесты

```kotlin
val engine = MockEngine { request ->
    assertEquals("/projects", request.url.encodedPath)
    respond("""[{"id":"1"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
val api = ProjectsApi(createHttpClient(engine, Json { ignoreUnknownKeys = true }), NetworkConfig("https://test"))
```
