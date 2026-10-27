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
  - `heartbeat.kmp.wasm` — + таргет `wasmJs` (браузер) только для боковой панели Chromium-расширения и модулей, которые
    она компилирует; браузерные тесты выключены — `commonTest` идёт на JVM, `check` только компилирует тесты под wasm;
  - `heartbeat.feature.api` / `heartbeat.feature.impl` — зависимости и ограничения для фич;
  - `heartbeat.metro` — Metro-плагин;
  - `heartbeat.train` — доля поезда в `:platform-main:desktop`: `assembleFoundation`/`assembleContracts`, `trainSeed` (seed установщика, `.seed`-jar вне classpath); `heartbeat.bundle` — сборщик бандла desktop (`:bundles:<name>`, только JVM), см. `bundles.md`; задачи: `:bundles:<name>:assembleBundle` (воспроизводимый jar), корневые `bundleManifest`
     (JSON поезда: id, версия, jar, SHA-256, `platformApi`) и `affectedBundles`
     (`-Pheartbeat.changedPaths=a,b` или `-Pheartbeat.changedFile=<файл>`);
  - `heartbeat.detekt` — detekt + ktlint-wrapper + compose-rules;
  - `heartbeat.jcef` — встроенный Chromium (JCEF): натив хоста из каталога, JDK-модули и JVM-аргументы, проверка закреплённого runtime и изолированные нативные тест-задачи.
- Новый модуль регистрируется в `settings.gradle.kts` (`include(":features:chat:api")`). Используй typesafe project accessors (`projects.core.logging`).
- `api(...)` только для типов, входящих в публичный контракт модуля; остальное `implementation(...)`.
- Проверяй правила зависимостей из `CLAUDE.md` при каждой правке `dependencies { }`: `features:*:impl` не зависит от другого `impl`; `core` не зависит от `features`/`design-system`.
- Configuration cache и build cache включены — не читай `System.getenv`/файлы на этапе конфигурации без `providers.*`.
- Изменение версии Kotlin/Compose/AGP — только отдельным изменением, проверь совместимость Metro и Compose compiler.
