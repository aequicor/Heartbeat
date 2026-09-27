# core:logging

Единственная точка логирования в проекте — фасад `Log` над Napier. Политика (что логировать) —
правило 6 в `CLAUDE.md`.

Зависит только от Napier (`implementation`: за пределами модуля Napier не виден). От модуля могут зависеть все.

## Использование

```kotlin
private val log = Log.tag("ChatRepository")        // тег = имя класса или инфраструктурный префикс (DI, SM/chat, NET…)

log.d { "load history chatId=$chatId" }            // лямбда: строка не строится, если уровень выключен
log.i { "prompt sent len=${prompt.length}" }
log.w(error) { "retry #$attempt" }                  // throwable передаётся всегда, когда он есть
log.e(error) { "generation failed chatId=$chatId" }
log.i { "token=${Log.redact(token)}" }              // явное скрытие значения → "***"
```

Инициализация — один раз в точке входа платформы, до создания DI-графа:

```kotlin
Log.init(isDebug = BuildConfig.DEBUG, sinks = listOf(crashReporterSink))
```

| Сборка | Минимальный уровень | Вывод |
|---|---|---|
| debug | `VERBOSE` | `DebugAntilog` Napier (Logcat / консоль / os_log) + `sinks` |
| release | `INFO` | только `sinks` (`LogSink` — файл, крэш-репортер) |

## Секреты

Каждое сообщение проходит через `Redactor`. Он вырезает `Bearer …`, `sk-…`, `api_key= / token= / password= / secret=…`
и e-mail. Это последняя линия защиты, а не разрешение логировать секреты: значения скрывай через `Log.redact(...)`.

Текст исключения (`throwable`) не очищается. Не клади секреты в сообщения исключений.

## Правила

- `println`, `android.util.Log`, `NSLog` и прямой `Napier` вне этого модуля запрещены (detekt `heartbeat:RawLoggingCall`, хук).
- `minLevel` — глобальное состояние, как и сам Napier: логирование должно работать до DI-графа и вне его.
  Это осознанное исключение из правила «никаких `object` с состоянием».

## Тесты

`./gradlew :core:logging:jvmTest`: фильтрация уровней без построения сообщения, передача throwable, очистка секретов.
