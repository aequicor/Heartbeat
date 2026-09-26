# core:network:api

Контракт сети для API-классов фич. Реализация — [core:network:impl](../impl/README.md), решение — [ADR-0005](../../../docs/adr/0005-network.md).
От модуля зависят `features:*:impl` и другие `core`; `features:*:api` — нет.

| Тип | Назначение |
|---|---|
| `HttpClient` (Ktor) | инжектится из графа; модуль экспортирует `ktor-client-core` (`get`, `post`, `body`, `timeout { }`, `retry { }`) |
| `NetworkConfig` | таймауты (`request` / `connect` / `socket`) и `maxRetries`; по умолчанию `NetworkConfig()` |
| `NetworkException` | ожидаемые сбои: `Http(status)`, `Timeout`, `Connectivity`, `InvalidResponse`; без `cause`, в сообщении только вид сбоя и класс исходного исключения — безопасно логировать `log.w(e)` |
| `networkResult { }` | выполняет запрос и декодирование, сбои сети → `Result.failure(NetworkException)` |

## Использование

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

- `CancellationException` пробрасывается — вызывающий отменён.
- Баги (всё, что не сбой сети и не ошибка декодирования ответа) пробрасываются как есть, в `Result` не прячутся.
  Ошибка сериализации тела запроса (DTO без `@Serializable`) — тоже баг.
- `networkResult` не логирует: запрос уже залогирован транспортом (тег `NET`), сбой логирует репозиторий, который его обработал.

## Тесты

`./gradlew :core:network:api:jvmTest`: маппинг статуса, таймаутов, `IOException`, ошибок декодирования без URL и тел; отмена, баги и ошибки сериализации запроса не оборачиваются.
