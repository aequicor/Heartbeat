---
name: logging
description: "Логирование в Heartbeat через core:logging (Napier) — фасад Log, инициализация Antilog по платформам и сборкам, адаптеры для FlowMVI StoreLogger и Koog handleEvents (HTTP логирует core:network:impl, state-machine — свой рантайм), DataStore/Room, редактирование секретов; аудит, что всё действие/IO залогировано. Используй при создании core:logging, добавлении нового источника событий или при проверке покрытия логами."
---

# Логирование

Политика (что логировать) — правило 6 в `CLAUDE.md`. Здесь — реализация.

## Фасад `core:logging`

```kotlin
package io.aequicor.heartbeat.core.logging

/** Project-wide logging facade over Napier. The only allowed logging entry point. */
class Log private constructor(private val tag: String) {
    fun v(message: () -> String) = write(LogLevel.VERBOSE, null, message)
    fun d(message: () -> String) = write(LogLevel.DEBUG, null, message)
    fun i(message: () -> String) = write(LogLevel.INFO, null, message)
    fun w(error: Throwable? = null, message: () -> String) = write(LogLevel.WARNING, error, message)
    fun e(error: Throwable? = null, message: () -> String) = write(LogLevel.ERROR, error, message)

    private fun write(level: LogLevel, error: Throwable?, message: () -> String) {
        if (level < minLevel) return                       // message lambda is not evaluated
        Napier.log(level.toNapier(), tag = tag, throwable = error, message = Redactor.redact(message()))
    }

    companion object {
        private var minLevel = LogLevel.INFO

        fun tag(tag: String): Log = Log(tag)

        /** Call once from platform-main before building the DI graph.
         *
         *  Debug builds log from DEBUG up so the console stays readable while streaming; `isTrace` adds
         *  VERBOSE (routine storage IO, streaming revisions, state/effect internals) for deep debugging — on desktop it is
         *  enabled with the `heartbeat.trace` system property or `HEARTBEAT_TRACE`. */
        fun init(isDebug: Boolean, isTrace: Boolean = false, sinks: List<LogSink> = emptyList()) {
            Napier.takeLogarithm() // repeated initialization replaces destinations
            minLevel = when {
                isTrace -> LogLevel.VERBOSE
                isDebug -> LogLevel.DEBUG
                else -> LogLevel.INFO
            }
            if (isDebug) Napier.base(platformDebugAntilog()) // platform console
            sinks.forEach { Napier.base(SinkAntilog(it)) }    // file / crash reporter in release
        }
    }
}

/** Project log levels, ordered by severity. */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR }
```

`Redactor` вырезает по регулярным выражениям API-ключи (`sk-…`, `Bearer …`, `x-api-key`), e-mail, и значения, помеченные `Secret<T>`.

JVM использует внутренний Antilog с собственным UTF-8 `ConsoleHandler`, без общего JUL-логгера:
повторная инициализация не накапливает обработчики. Формат — `HH:mm:ss.SSS [LEVEL] Tag - сообщение`;
переводы строк сообщения экранируются, throwable сохраняет полный стек. Android/iOS используют Napier `DebugAntilog`.

Уровни: `V` — обычные чтения/записи KV и vault, потоковые ревизии транскрипта, проекции usage/configuration/
permissions, входящие интенты, stay/no-change, регистрация и жизненный цикл эффектов, отражение в стор;
`D` — полезные решения и одноразовые outputs; `I` — действия пользователя, переходы машин, запросы,
изменения конфигурации и очистки. Outputs без подписчиков — `D`, переполнение буфера — `W`.
Некорректные/отклонённые интенты и реальные ошибки сохраняют `W`/`E`.

## Защита от флуда

- Оцени частоту до добавления лога: поток токенов/дельт, polling, ревизии, проекции usage/configuration/permissions
  и обычный IO должны оставаться на `VERBOSE` даже в debug-сборке. Требование покрытия логами не означает `DEBUG`.
- Помечай такие функции/accessors `@HighFrequency` из `core:logging`, в том числе вынесенные частые helpers.
  Detekt `HighFrequencyLog` запрещает в них `d/i/debug/info`, включая вложенные лямбды; он также проверяет
  трансформации `update/getAndUpdate/updateAndGet`, которые могут исполняться повторно.
  Проверка синтаксическая: она не прослеживает вызовы helpers, наследование и фактическую частоту событий.
  Обычный `collect`/цикл сам по себе не доказывает частоту: для частого обработчика нужна аннотация.
- На `DEBUG`/`INFO` оставляй начало/итог операции и значимые изменения после сравнения старого и нового значения.
  Для длительного потока при необходимости делай сводку с ограничением по времени и числом пропущенных событий;
  «каждая N-я дельта» не ограничивает число строк в секунду. Сводку выноси за границу частого обработчика.
- Не дублируй одну операцию в адаптере, репозитории, машине и сторе, если её уже пишет инфраструктура.
  Не повышай глобальный порог логирования ради одного шумного источника.
- Ошибки не переводи в `VERBOSE`: сохраняй `WARN`/`ERROR` и throwable. Для повторяющейся ошибки
  логируй первое возникновение и ограниченные по времени сводки с числом повторов; разные ошибки не объединяй.
- `@Suppress("HighFrequencyLog")` и повышение рутинного события до `WARN` ради обхода проверки запрещены.
  Проверяй регрессию: обычный debug-сеанс не должен печатать строку на каждую дельту,
  а trace должен сохранять подробную диагностику.

## Адаптеры (всё централизованно)

```kotlin
// FlowMVI — используется в heartbeatStore { configure { logger = NapierStoreLogger } ; enableLogging() }
object NapierStoreLogger : StoreLogger {
    override fun log(level: StoreLogLevel, tag: String?, message: () -> String) {
        val log = Log.tag("MVI/${tag ?: "store"}")
        when (level) {
            StoreLogLevel.Trace -> log.v(message)
            StoreLogLevel.Debug -> log.d(message)
            StoreLogLevel.Info -> log.i(message)
            StoreLogLevel.Warn -> log.w(message = message)
            StoreLogLevel.Error -> log.e(message = message)
        }
    }
}

// State-machine — адаптер не нужен: рантайм core:state-machine:impl сам пишет в SM/<name> интенты, переходы,
// stay, отклонения, эффекты (старт/завершение/отмена/ошибка), outputs, старт/стоп; движок KStateMachine — в V.
// Стор ← машина — core:state-machine:flowmvi-ext (MVI/<store>).

// Ktor — см. скилл network; Koog — см. скилл ai-koog; Decompose — core:navigation; DataStore/Room — скилл data-storage.
```

## Аудит покрытия (когда просят «проверить логирование»)

1. Найди запрещённое: `Grep "println|android.util.Log|NSLog|Napier\\."` вне `core/logging`.
2. Найди `catch` без лога: `Grep -A3 "catch \\("` и проверь тело.
3. Для каждого репозитория/эффекта/инструмента агента — есть ли лог операции на подходящем уровне
   (`v` для частого внутреннего IO, `d`/`i` для значимых операций) и `e`/`w` на ошибке.
4. Проверь частые пути (дельты, polling, ревизии, проекции): `@HighFrequency`, уровень `v`, отсутствие дублирования
   и ограниченная частота сводок; диагностика detekt не должна советовать `d` для частых событий.
5. Для новых источников событий (новая библиотека, платформенный колбэк) — есть ли адаптер в `core`.
6. Проверь, что секреты и полные промпты не попадают в `message` (особенно в `data class toString()` — переопредели или используй `Secret<T>`).

Итог — список `[файл:строка] что не логируется / что логируется лишнее`.
