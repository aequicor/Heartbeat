---
name: logging
description: "Логирование в Heartbeat через core:logging (Napier) — фасад Log, инициализация Antilog по платформам и сборкам, адаптеры для FlowMVI StoreLogger и Koog handleEvents (HTTP логирует core:network:impl, state-machine — свой рантайм), DataStore/Room, редактирование секретов; аудит, что всё действие/IO залогировано. Используй при создании core:logging, добавлении нового источника событий или при проверке покрытия логами."
---

# Логирование

Политика (что/где/каким уровнем) — [docs/ai/logging-policy.md](../../../docs/ai/logging-policy.md). Здесь — реализация.

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

        /** Call once from platform-main before building the DI graph. */
        fun init(isDebug: Boolean, extra: List<Antilog> = emptyList()) {
            minLevel = if (isDebug) LogLevel.VERBOSE else LogLevel.INFO
            if (isDebug) Napier.base(DebugAntilog())   // Logcat / NSLog / stdout
            extra.forEach(Napier::base)                 // file / crash reporter in release
        }
    }
}

/** Project log levels, ordered by severity. */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARNING, ERROR }
```

`Redactor` вырезает по регулярным выражениям API-ключи (`sk-…`, `Bearer …`, `x-api-key`), e-mail, и значения, помеченные `Secret<T>`.

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
// Стор ← машина — core:state-machine:flowmvi-ext (MVI/<store>). См. docs/adr/0004-state-machine.md.

// Ktor — см. скилл network; Koog — см. скилл ai-koog; Decompose — core:navigation; DataStore/Room — скилл data-storage.
```

## Аудит покрытия (когда просят «проверить логирование»)

1. Найди запрещённое: `Grep "println|android.util.Log|NSLog|Napier\\."` вне `core/logging`.
2. Найди `catch` без лога: `Grep -A3 "catch \\("` и проверь тело.
3. Для каждого репозитория/эффекта/инструмента агента — есть ли `d` на входе и `e`/`w` на ошибке.
4. Для новых источников событий (новая библиотека, платформенный колбэк) — есть ли адаптер в `core`.
5. Проверь, что секреты и полные промпты не попадают в `message` (особенно в `data class toString()` — переопредели или используй `Secret<T>`).

Итог — список `[файл:строка] что не логируется / что логируется лишнее`.
