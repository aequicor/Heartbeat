# Политика логирования

Требование продукта: **любое действие, изменение состояния, запрос в сеть, обращение к хранилищу и изменение конфигурации сопровождается логом.**
Чтобы это не превращалось в ручную рутину, большая часть логов генерируется **централизованно** адаптерами в `core:*`. Ручные логи пишутся только там, где адаптера нет.

## Фасад

Весь код логирует через `io.aequicor.heartbeat.core.logging.Log` (обёртка над Napier). Прямой `Napier.*` допустим только внутри `core:logging`.

```kotlin
private val log = Log.tag("ChatRepository")

log.d { "load history chatId=$chatId" }          // лямбда — строка не строится, если уровень выключен
log.i { "prompt sent chatId=$chatId len=${prompt.length}" }
log.w(error) { "retry #$attempt" }
log.e(error) { "generation failed chatId=$chatId" }
```

## Что логирует инфраструктура автоматически

| Событие | Где подключено | Уровень |
|---|---|---|
| Intent / Action / смена State стора | плагин логирования в `core:mvi` (`heartbeatStore { }`) | D (intent/action), V (state diff) |
| State-machine: приход интента и его источник (`← Open (effect Load)`) | рантайм `core:state-machine:impl`, на каждую машину | D |
| State-machine: переход (`Idle --Open--> Loading`) / `stay` (`Ready ~Draft~> Ready`) / отклонённый интент | то же | I / D / W |
| State-machine: эффект — старт, завершение, отмена при выходе из состояния / ошибка | то же | D / E (с throwable) |
| State-machine: output, старт/restore/стоп машины, регистрация в реестре | то же | D, I (старт/стоп) |
| Отправка интента в чужую машину через `MachineRegistry` / машина не запущена | `core:state-machine:impl` | I / W |
| Стор ← машина: отражённое состояние и outputs, отклонённый `sendTo` | `core:state-machine:flowmvi-ext` (`MVI/<store>`) | D / W |
| Навигация (операция, путь хоста, `serialName` маршрута, стек — без полей маршрутов), результаты, deep links (шаблон, не ссылка) | хосты `core:navigation:impl` | I |
| HTTP-запрос/ответ, статус, длительность | Ktor `Logging` с адаптером в `core:network` | I (метод, url, статус, ms), D (заголовки без секретов) |
| Вызовы LLM, инструменты агента, токены, ошибки | Koog event handler в `core:ai` | I (модель, tool, tokens, ms), D (контент — только debug-сборки) |
| Запись/чтение DataStore | логирующая обёртка в `core:datastore` | D (ключ), I (изменение конфигурации) |
| Изменение тогла | `core:feature-toggles` | I (`toggle key: old -> new, source`) |
| Транзакции/миграции БД | `core:database` (callback'и + логирующие репозитории) | I (миграции), D (DAO-операции) |
| Жизненный цикл компонентов | `core:navigation` (Essenty lifecycle callbacks) | V |

## Что логируется вручную

- Действия пользователя, не проходящие через стор (редко; обычно это intent — уже залогирован).
- Методы репозиториев: `d` на входе с ключевыми параметрами, `e` на ошибке.
- Ветвления бизнес-логики, в которых теряется информация (fallback, кэш-хит/промах).
- Любой `catch` — лог обязателен (`w` если восстановились, `e` если нет). Пустой `catch` запрещён.

## Теги

- Тег = имя класса-источника (`ChatStore`, `ChatMachine`, `HttpClient`).
- Инфраструктурные теги с префиксом: `SM/<machine>` (только имена классов интентов/состояний/эффектов — их данные не логируются), `MVI/<store>`, `NAV`, `NET`, `AI`, `DB`, `DS`, `FT`, `DI` (создание/закрытие скоупов).

## Запрещено логировать

- API-ключи, токены, пароли, заголовки `Authorization`, cookies — редактируются `Log.redact()` и Ktor `sanitizeHeader`.
- Полный текст пользовательских промптов/ответов LLM и файлов — только в debug-сборке на уровне `D`; в release — длина, хэш, id.
- Персональные данные пользователя.

## Автоматическая проверка (detekt)

Политика и правило обработки ошибок проверяются собственным набором правил detekt `heartbeat`
([lint/detekt-rules](../../lint/detekt-rules), конфиг — раздел `heartbeat` в [config/detekt/detekt.yml](../../config/detekt/detekt.yml)).
Правила синтаксические (без type resolution), лог-вызов распознаётся по форме `<log|logger|Log|xxxLog>.<v|d|i|w|e|…>(…)`.

| Правило | Что ловит |
|---|---|
| `SwallowedError` | `catch`, `Flow.catch { }`, `onFailure { }`, `getOrElse { }`, `recover { }`, `fold(onFailure = …)`, `CoroutineExceptionHandler` без `log.w/e(throwable)` или проброса (`throw`, `Result.failure(e)`, `resumeWithException(e)`…) |
| `CancellationSwallowed` | `catch (e: CancellationException)` без `throw e`; `catch (Exception/Throwable/…)` в корутине без предшествующего `catch (e: CancellationException) { throw e }` / `ensureActive()`; `runCatching` в корутине (→ `suspendRunCatching`) |
| `GenericExceptionCaught` | `catch` слишком общих типов (`Throwable`, `RuntimeException`, `Error`…); `catch (e: Exception)` разрешён только после `catch (e: CancellationException) { throw e }` или `if (e is CancellationException) throw e` — заменяет стандартное `TooGenericExceptionCaught` |
| `UnhandledResultFailure` | `runCatching { }` без `onFailure/getOrElse/fold/recover` или схлопнутый `getOrNull()/getOrDefault()` |
| `StateChangeNotLogged` | изменение `MutableStateFlow`/`MutableSharedFlow`/`mutableStateOf` в классе без лога в той же функции (кроме `@Composable`, `core:mvi`, `core:state-machine`) |
| `DataAccessNotLogged` | публичные методы `*Repository`/`*DataSource`/`*Storage`/`*Api`/`*Client` без лога |
| `LoggingInfrastructureBypass` | `HttpClient`, DataStore-фабрики, `Room.databaseBuilder`, FlowMVI `store`, `createStateMachine` вне своего логирующего `core:*`-модуля |
| `RawLoggingCall` | `println`, `print`, `printStackTrace()`, `System.out/err`, `android.util.Log`, `NSLog`, Napier вне `core:logging` |
| `SensitiveDataLogged` | ключи/токены/пароли/`authorization`/cookie в лог-вызовах без `redact(...)` (разрешено `.length`, `.isBlank()`…) |

Дополнительно включены стандартные правила detekt: `SwallowedException`, `EmptyCatchBlock`
(имена `ignored`/`expected` **не** освобождают от проверки), `PrintStackTrace`, `SuspendFunSwallowedCancellation` (в задачах с type resolution).
`@Suppress` для `SwallowedError`, `CancellationSwallowed`, `RawLoggingCall`, `SensitiveDataLogged` запрещён (`ForbiddenSuppress`);
легальный способ передать ошибку дальше — расширить `propagationCalls` в конфиге.

## Уровни и сборки

| Сборка | Минимальный уровень | Antilog |
|---|---|---|
| debug | VERBOSE | `DebugAntilog` (консоль/Logcat/os_log) |
| release | INFO | файловый/крэш-репортер (TBD, ADR) |

Инициализация — один раз в `platform-main` до создания DI-графа: `Log.init(isDebug)`.
