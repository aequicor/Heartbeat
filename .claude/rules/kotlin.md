---
paths:
  - "**/*.kt"
---

# Kotlin-код (все модули)

- Стиль — `kotlin.code.style=official` + detekt (ktlint-wrapper, compose-rules). Не спорь с форматтером: `./gradlew detekt --auto-correct`.
- Пакет = `io.aequicor.heartbeat.<group>.<module>…`; путь файла совпадает с пакетом.
- Публичное API модуля минимально: по умолчанию `internal`. `public` — только то, что реально нужно другим модулям (для `api`-модулей фич — контракт).
- Логирование: `private val log = Log.tag("<ClassName>")` из `core:logging`. Запрещены `println`, `print`, `System.out`, `android.util.Log`, `NSLog`, прямой `Napier` вне `core:logging`.
- Не создавай поток `DEBUG`/`INFO` на каждый токен, chunk, ревизию, polling tick, проекцию состояния или обычный IO: это `log.v`, доступный через trace. Частые функции/accessors (включая выделенные helpers) помечай `@HighFrequency` из `core:logging`; `heartbeat:HighFrequencyLog` проверяет их и повторяемые `update`/`getAndUpdate`/`updateAndGet`-лямбды. Итог операции логируй вне частого обработчика; `WARN`/`ERROR` сохраняй для реальных ошибок. Подавление `HighFrequencyLog` запрещено.
- Каждый `catch` (и `Flow.catch`, `onFailure`, `getOrElse`, `CoroutineExceptionHandler`) логирует ошибку `log.w(e)`/`log.e(e)` или пробрасывает её — проверяет detekt `heartbeat:SwallowedError`. `catch (e: CancellationException)` → rethrow. Предпочитай `runCatching` только с явной обработкой `CancellationException` (используй `suspendRunCatching` из `core:common`).
- Корутины: без `GlobalScope`, без `runBlocking` в main-коде, диспетчеры через `DispatcherProvider` (инжект). `suspend`-функции main-safe.
- `!!` запрещён (кроме тестов). `lateinit` — только для DI-полей платформенных классов.
- Иммутабельность: `data class` с `val`, коллекции `List/Map` (для Compose-состояний — `kotlinx.collections.immutable`).
- Никаких `object` с изменяемым состоянием — используй `@SingleIn(AppScope::class)` в Metro.
- `expect/actual` — только для платформенных примитивов (драйверы, пути, engine). Предпочитай интерфейс в `commonMain` + реализацию через DI.
- KDoc на публичных декларациях `api`-модулей и `core` (правила detekt `comments`).
- `@Suppress("RuleName")` — только с комментарием, почему.
