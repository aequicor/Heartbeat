---
paths:
  - "**/src/*Test/**"
  - "**/src/test/**"
---

# Тесты

- Основной слой тестов — `commonTest` (`kotlin.test` + `kotlinx-coroutines-test`). Платформенные тесты — только для `actual`-кода.
- Быстрый прогон на Windows: `./gradlew :<module>:jvmTest`. Полный: `./gradlew allTests` (iOS — только на macOS).
- State-machine: тест в `api/src/commonTest` на каждый переход, включая запрещённые (событие игнорируется/отклоняется). Эффекты — фейки.
- FlowMVI-сторы: тест-DSL FlowMVI (`subscribeAndTest` / `test`), проверяем intent → state/action.
- Репозитории: in-memory Room (`Room.inMemoryDatabaseBuilder` + `BundledSQLiteDriver`), DataStore во временной директории, Ktor `MockEngine`.
- Koog-агенты: мок `PromptExecutor` (без сетевых вызовов в тестах).
- Имена: `` `send prompt from Idle moves to Generating`() `` — поведение, не метод.
- Никаких `Thread.sleep`/`delay` для синхронизации — `runTest` + `advanceUntilIdle`/`Turbine`.
- `!!` и `@Suppress` в тестах допустимы.
