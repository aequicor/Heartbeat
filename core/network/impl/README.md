# core:network:impl

Реализация [core:network:api](../api/README.md): `HttpClient` приложения. **Подключается только в `:platform-main:di-bundle`.**
Решение — [ADR-0005](../../../docs/adr/0005-network.md).

| Класс | Роль |
|---|---|
| `NetworkBindings` | `@SingleIn(AppScope)` `HttpClient`; закрывается с app-скоупом. `NetworkConfig` — из графа, если там есть биндинг, иначе `NetworkConfig()` |
| `AndroidEngineBindings` / `JvmEngineBindings` / `IosEngineBindings` | engine платформы: OkHttp (Android, desktop), Darwin (iOS); закрывается после клиента |
| `createHttpClient` | конфигурация клиента (ниже); `internal`, тестам передаётся `MockEngine` и `retryDelay` |
| `NetworkLogging` | плагин логов `NET` |

## Конфигурация

| Плагин | Поведение |
|---|---|
| `expectSuccess = true` | не-2xx → `ResponseException` → `NetworkException.Http` в `networkResult` |
| `ContentNegotiation` | `Json { ignoreUnknownKeys = true }` |
| `HttpRequestRetry` | только `GET`/`HEAD`/`OPTIONS`/`PUT`/`DELETE`; на 5xx и `IOException`, кроме таймаутов; экспоненциальная задержка (1 с, 2 с … + до 1 с случайно), `Retry-After` не учитывается |
| `HttpTimeout` | из `NetworkConfig`, отдельно на каждую попытку |
| `NetworkLogging` | внутренний `Send`-перехватчик: видит каждую попытку |
| `HttpResponseValidator` | внешний: логирует request timeout (в `Send` он приходит как отмена) |

## Логи (тег `NET`)

| Уровень | Что |
|---|---|
| `I` | `GET https://host:8443/path?page=***&q=*** -> 200 (123 ms)`, `retry #1 GET …`, прерванная попытка (`interrupted`), создание клиента с `NetworkConfig` |
| `W` | не-2xx ответ; сбой попытки — по имени класса исключения (`failed: ConnectException`); `timed out` — request timeout. Каждый сбой — один раз |
| `D` | заголовки запроса и ответа; значения `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` и заголовков с `token`/`secret`/`key`/`session`/`auth` в имени — `***` |

Тела запросов и ответов не логируются никогда. Значения query скрыты всегда. Исключения Ktor в лог не передаются:
в их сообщениях полный URL и тело ответа. Заголовки видны только в debug-сборке
(в release минимальный уровень `Log` — `INFO`).

## Платформы

- Android: приложению нужно разрешение `android.permission.INTERNET` (добавит `platform-main:android` при миграции).
- Desktop: OkHttp тянет `slf4j-api`; без провайдера SLF4J выводит одно предупреждение при старте.
- iOS: Darwin (NSURLSession), ATS-исключения — в `Info.plist` приложения.

## Тесты

`./gradlew :core:network:impl:jvmTest` (MockEngine): JSON, статусы, ретраи и backoff, POST без ретраев, таймаут без
ретраев и с одним `W`, битый JSON, формат логов, отсутствие секретов, значений query и тел в логах и в цепочке `cause`.
Граф — `NetworkIntegrationTest` в [platform-main/di-bundle](../../../platform-main/di-bundle/README.md).
