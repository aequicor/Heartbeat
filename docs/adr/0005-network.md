# ADR-0005: Сеть — core:network:api / impl на Ktor 3

- Статус: принято
- Дата: 2026-09-26
- Затрагивает: `core:network:{api,impl}`, `platform-main:di-bundle`, `lint:detekt-rules` (текст `LoggingInfrastructureBypass`),
  `gradle/libs.versions.toml` (Ktor 3.6.0), фичи с HTTP

> Номер 0004 зарезервирован за ADR про `core:state-machine` (отдельный PR, в этой ветке файла нет).

## Контекст

Фичам нужен HTTP-клиент, который:

- работает на Android, iOS и desktop (JVM) без кода платформ в фичах;
- логирует каждый запрос по [logging-policy](../ai/logging-policy.md) (метод, URL, статус, длительность) и никогда не
  логирует секреты и тела с пользовательскими данными;
- не повторяет неидемпотентные запросы (генерация, оплата, отправка сообщения) и не держит пользователя минутами на ретраях;
- отдаёт фичам ожидаемые сбои (статус, таймаут, нет сети, битый ответ) в одном виде, не пряча баги и отмену;
- как и остальные `core`-модули с реализацией, разделён на `api` (контракт) и `impl` (видит только `di-bundle`, ADR-0002).

## Решение

**`core:network:api`** — `api(ktor-client-core)`, чтобы API-классы фич работали с `HttpClient` напрямую (`get`, `body`,
`timeout { }`, `retry { }`), плюс:

- `NetworkConfig` — таймауты и `maxRetries`, без base URL: адреса бэкендов — конфиг фичи;
- `NetworkException` (`Http(status)`, `Timeout`, `Connectivity`, `InvalidResponse`) — без `cause` и без URL/тел в сообщении
  (только имя класса исходного исключения): сообщения исключений Ktor содержат полный URL (`[url=…]`), тело ответа
  (`ResponseException`) и фрагменты JSON, а throwable `Log` не редактирует;
- `networkResult { }` — ожидаемые сбои → `Result.failure(NetworkException)`; `CancellationException` и прочие исключения
  пробрасываются, в том числе ошибка сериализации тела запроса (это баг); ничего не логирует (запрос уже залогирован транспортом, сбой логирует обработавший его репозиторий).

**`core:network:impl`** — один `HttpClient` на процесс (`@SingleIn(AppScope)`, закрывается с app-скоупом):

- engine — Metro-контейнеры в платформенных source set'ах (`OkHttp` на Android и JVM, `Darwin` на iOS; граф и так
  per-platform из-за контрибуций вроде `PlatformInfo`), а не `expect/actual`: detekt с type resolution анализирует `expect/actual` одного модуля с ошибками компиляции;
- `expectSuccess = true`, `ContentNegotiation` с `Json { ignoreUnknownKeys = true }`;
- `HttpRequestRetry` только для `GET`/`HEAD`/`OPTIONS`/`PUT`/`DELETE`, на 5xx и `IOException`, кроме таймаутов;
  экспоненциальная задержка без учёта `Retry-After`;
- `HttpTimeout` из `NetworkConfig`, отдельно на каждую попытку;
- собственный плагин `NetworkLogging` (тег `NET`) вместо `ktor-client-logging`: одна строка `I` на попытку с методом,
  URL без значений query, статусом и длительностью; `W` на не-2xx и сбой (имя класса исключения, без throwable);
  `D` — заголовки со скрытыми значениями чувствительных; тела — никогда. Request timeout Ktor доставляет в плагины как
  `CancellationException`, поэтому его логирует (`W`, один раз) внешний `HttpResponseValidator`, где причина уже развёрнута;
- `NetworkConfig` — опциональная зависимость провайдера (`config: NetworkConfig = NetworkConfig()`): граф платформы
  переопределяет её обычным `@Provides`.

## Альтернативы

| Вариант | Плюсы | Минусы | Почему нет |
|---|---|---|---|
| `ktor-client-logging` + адаптер `Logger` | готовый плагин, формат `OkHttp` с длительностью | `Logger.log(message)` без уровня: итог запроса и заголовки идут одним уровнем; тела включаются одной настройкой `LogLevel` | политике нужны `I` для итога, `W` для сбоев и `D` для заголовков; тела запрещены |
| Ретраи всех методов (`retryOnExceptionOrServerErrors`) | проще | повтор `POST` дублирует генерацию/отправку | неидемпотентные запросы повторяет только фича, осознанно |
| Ретраи таймаутов | переживает медленную сеть | пользователь ждёт `(n + 1) × requestTimeout` | таймаут уже долгий; при необходимости — `retry { }` на запросе |
| Ошибки как исключения без `networkResult` | меньше API | каждый репозиторий повторяет разбор `ResponseException`/`IOException`/таймаутов | один маппинг в `core` |
| Один модуль `core:network` | меньше модулей | фичи видят engine и DI-контейнеры | api/impl, как у остальных `core` с реализацией |
| `expect fun platformEngine()` | меньше кода | ошибки анализа detekt с type resolution | DI-контейнеры per-platform |

## Последствия

- API фичи: `@Inject` `HttpClient` + свой конфиг адреса, запросы в `networkResult { }`; см. скилл `network`.
- Тесты API фич — на `HttpClient(MockEngine)` с тем же JSON-конфигом: `createHttpClient` — `internal` в `impl`,
  а `impl` фичам недоступен. Если расхождение конфигов станет проблемой — отдельный модуль `core:network:testing`.
- Android-приложению нужно `android.permission.INTERNET` (при миграции `platform-main:android`).
- OkHttp на desktop тянет `slf4j-api`: без провайдера — одно предупреждение SLF4J при старте.
- Обновлены: `docs/ai/{architecture,logging-policy,tech-stack}.md`, скиллы `network`, `di-metro`, `logging`, `CLAUDE.md`.
