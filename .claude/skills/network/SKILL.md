---
name: network
description: "Сетевой слой Heartbeat на Ktor 3 (core:network:api / impl) — HttpClient приложения с engine per-platform (OkHttp / Darwin), JSON, таймауты, ретраи только идемпотентных запросов, логи NET с редактированием секретов, NetworkConfig, ошибки NetworkException через networkResult { }, API-клиенты фич, тесты через MockEngine. Используй при добавлении HTTP-запросов или изменении клиента."
---

# Сеть (Ktor)

Документация: https://ktor.io/docs/client-create-new-application.html . Версия — [tech-stack.md](../../../docs/ai/tech-stack.md).
Решение — [ADR-0005](../../../docs/adr/0005-network.md). LLM-провайдеры ходят через Koog (скилл `ai-koog`), а не через этот клиент.

## Модули

| Модуль | Кто зависит | Что внутри |
|---|---|---|
| `core:network:api` | `features:*:impl`, другие `core` | `HttpClient` (`api(ktor-client-core)`), `NetworkConfig`, `NetworkException`, `networkResult { }` |
| `core:network:impl` | только `platform-main:di-bundle` | `createHttpClient`, плагин `NetworkLogging`, `NetworkBindings`, `<Platform>EngineBindings` |

`features:*:api` от сети не зависит (IO живёт в `impl`).

## Клиент (`core:network:impl`)

Один `HttpClient` на процесс (`@SingleIn(AppScope)`), закрывается вместе с app-скоупом, engine — после клиента.
Engine контрибутится per-platform: `AndroidEngineBindings` / `JvmEngineBindings` (OkHttp), `IosEngineBindings` (Darwin).

Порядок плагинов важен (внутренний `Send`-перехватчик видит каждую попытку):

```
expectSuccess = true                     не-2xx → ResponseException
ContentNegotiation(json)                 Json { ignoreUnknownKeys = true }
HttpRequestRetry                         GET/HEAD/OPTIONS/PUT/DELETE: 5xx и IOException (не таймауты); POST/PATCH — никогда
HttpTimeout                              request / connect / socket из NetworkConfig — на каждую попытку
NetworkLogging                           I: "GET https://host/path?q=*** -> 200 (123 ms)", W: не-2xx / сбой (имя класса), D: заголовки
HttpResponseValidator                    W: request timeout (в Send Ktor несёт его как отмену)
```

- Тела запросов/ответов не логируются никогда. Значения query и чувствительных заголовков (`Authorization`, `Cookie`,
  `Set-Cookie`, `*token*`, `*key*`, `*secret*`, `*session*`, `*auth*`) — `***`.
- Ретраи логируются `I`: `retry #1 GET https://…`.
- Настройки — `NetworkConfig` (таймауты, `maxRetries`). По умолчанию — `NetworkConfig()`; чтобы поменять, предоставь
  `NetworkConfig` в графе (`@Provides` в графе платформы). Для одного запроса — `timeout { }` / `retry { }` в билдере.
- Создавать `HttpClient` вне `io.aequicor.heartbeat.core.network` запрещено (detekt `heartbeat:LoggingInfrastructureBypass`).

## API фичи (`impl/data`)

```kotlin
@Inject
internal class ProjectsApi(private val client: HttpClient, private val endpoints: ProjectsEndpoints) {
    private val log = Log.tag("ProjectsApi")

    suspend fun list(): Result<List<ProjectDto>> {
        log.d { "list projects" }
        return networkResult { client.get("${endpoints.baseUrl}/projects").body() }
    }
}
```

- Base URL — конфиг фичи из DI (не хардкод и не `core:network`: у разных фич разные бэкенды).
- DTO — `@Serializable` в `impl/data` (плагин `kotlinSerialization` в модуле фичи), маппинг в domain там же.
- `networkResult { }` возвращает ожидаемые сбои как `Result.failure(NetworkException)`:
  `Http(status)`, `Timeout`, `Connectivity`, `InvalidResponse`. `CancellationException` и баги (в том числе ошибка
  сериализации тела запроса) пробрасываются как есть. Сам он ничего не логирует — транспорт уже залогировал запрос.
- `NetworkException` безопасно логировать с throwable: у него нет `cause`, а в сообщении нет URL и тел. Исключения Ktor
  (`ResponseException`, таймауты) так логировать нельзя — в их сообщениях полный URL и тело ответа. Поэтому запросы
  только внутри `networkResult { }`.
- Репозиторий логирует обработанный сбой и превращает его в доменную ошибку → `Internal`-интент машины:

```kotlin
api.list().fold(
    onSuccess = { ProjectsIntent.Loaded(it.map(ProjectDto::toDomain)) },
    onFailure = { e ->
        log.w(e) { "projects not loaded" } // heartbeat:SwallowedError — каждый обработчик логирует свою ошибку
        ProjectsIntent.Failed
    },
)
```

## Тесты

`createHttpClient` — `internal` в `impl`, и от `impl` фичи зависеть не могут. Поэтому API фичи тестируют на клиенте
с `MockEngine` и тем же JSON-конфигом (зависимости `commonTest`: `ktor-client-mock`, `ktor-client-content-negotiation`,
`ktor-serialization-kotlinx-json`):

```kotlin
val engine = MockEngine { request ->
    assertEquals("/projects", request.url.encodedPath)
    respond("""[{"id":"1"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}
val client = HttpClient(engine) {
    expectSuccess = true
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}
val api = ProjectsApi(client, ProjectsEndpoints("https://test"))
```

Поведение самого клиента (ретраи, таймауты, логи, редактирование) покрыто в `core:network:impl`:
`./gradlew :core:network:api:jvmTest :core:network:impl:jvmTest`; граф — `:platform-main:di-bundle:jvmTest`.
