---
paths:
  - "**/*.gradle.kts"
  - "gradle/libs.versions.toml"
  - "build-logic/**"
  - "gradle.properties"
---

# Gradle

- Все версии и координаты — только в `gradle/libs.versions.toml`. Никаких строк `"group:artifact:version"` в `build.gradle.kts`.
- Модули подключают convention-плагины из `build-logic/` вместо копипасты конфигурации:
  - `heartbeat.kmp.library` — таргеты `android`, `jvm`, `iosArm64`, `iosSimulatorArm64`, toolchain, detekt;
  - `heartbeat.kmp.compose` — + Compose Multiplatform;
  - `heartbeat.feature.api` / `heartbeat.feature.impl` — зависимости и ограничения для фич;
  - `heartbeat.metro` — Metro-плагин;
  - `heartbeat.detekt` — detekt + ktlint-wrapper + compose-rules.
- Новый модуль регистрируется в `settings.gradle.kts` (`include(":features:chat:api")`). Используй typesafe project accessors (`projects.core.logging`).
- `api(...)` только для типов, входящих в публичный контракт модуля; остальное `implementation(...)`.
- Проверяй правила зависимостей из `docs/ai/architecture.md` при каждой правке `dependencies { }`: `features:*:impl` не зависит от другого `impl`; `core` не зависит от `features`/`design-system`.
- Configuration cache и build cache включены — не читай `System.getenv`/файлы на этапе конфигурации без `providers.*`.
- Изменение версии Kotlin/Compose/AGP — только отдельным изменением с ADR, проверь совместимость Metro и Compose compiler.
