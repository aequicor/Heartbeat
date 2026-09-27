---
name: module-setup
description: "Настройка Gradle-инфраструктуры Heartbeat — build-logic с convention-плагинами (heartbeat.kmp.library, kmp.compose, feature.api/impl, metro, detekt), добавление библиотек в version catalog, создание core/design-system модулей, миграция шаблона androidApp/desktopApp/shared в platform-main. Используй при создании нового Gradle-модуля, подключении detekt или первичной миграции проекта."
---

# Gradle: build-logic и модули

Правила — `.claude/rules/gradle.md`. Версии/координаты — `gradle/libs.versions.toml`. Конфиг detekt уже лежит в `config/detekt/detekt.yml`.

## 1. build-logic (один раз)

```
build-logic/
  settings.gradle.kts         versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
  convention/build.gradle.kts `kotlin-dsl`; compileOnly: kotlin-gradle-plugin, android gradle plugin, compose, metro, detekt, ksp, room
  convention/src/main/kotlin/
    HeartbeatKmpLibraryPlugin.kt   id heartbeat.kmp.library
    HeartbeatKmpComposePlugin.kt   id heartbeat.kmp.compose
    HeartbeatFeatureApiPlugin.kt   id heartbeat.feature.api
    HeartbeatFeatureImplPlugin.kt  id heartbeat.feature.impl
    HeartbeatMetroPlugin.kt        id heartbeat.metro
    HeartbeatDetektPlugin.kt       id heartbeat.detekt  (применяется всеми остальными)
    HeartbeatRoomPlugin.kt         id heartbeat.room
```

Корневой `settings.gradle.kts`: `pluginManagement { includeBuild("build-logic") }`, `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")`.

### Что делает каждый плагин

| Плагин | Содержимое |
|---|---|
| `heartbeat.kmp.library` | `kotlin("multiplatform")`, `com.android.kotlin.multiplatform.library`; таргеты `android { namespace = "io.aequicor.heartbeat.<path>" }`, `jvm()`, `iosArm64()`, `iosSimulatorArm64()`; `jvmToolchain(17)`; `explicitApi()` для `core` и `api`; `commonTest` ← kotlin-test, coroutines-test; + `heartbeat.detekt` |
| `heartbeat.kmp.compose` | + `org.jetbrains.compose`, `kotlin.plugin.compose`; compose runtime/foundation/ui/resources; compose compiler reports в `build/compose-reports` |
| `heartbeat.feature.api` | `kmp.library` + `metro` + `kotlinx-serialization`; deps: `core:state-machine:api`, `core:navigation:api`, `core:common`; **запрет** Compose-плагина |
| `heartbeat.feature.impl` | `kmp.compose` + `metro` + `serialization`; deps: `core:*` (logging, mvi, navigation, `state-machine:api` + `state-machine:flowmvi-ext`, feature-toggles:api, resources), `design-system:components`, `design-system:theme`; проверка: падение конфигурации, если в зависимостях есть `:features:*:impl` |
| `heartbeat.metro` | плагин `dev.zacsweers.metro` |
| `heartbeat.detekt` | плагин `dev.detekt`; `buildUponDefaultConfig = true`; `config.setFrom(rootProject.file("config/detekt/detekt.yml"))`; `parallel = true`; `source` = все `src/*/kotlin`; `detektPlugins(libs.detekt.ktlint.wrapper)`, `detektPlugins(libs.compose.rules.detekt)`; baseline `detekt-baseline.xml` |
| `heartbeat.room` (`RoomConventionPlugin`, готов) | `heartbeat.kmp.library` + `androidx.room` (`schemaDirectory("$projectDir/schemas")`) + `com.google.devtools.ksp`; `room-compiler` в `kspAndroid`/`kspJvm`/`kspIosArm64`/`kspIosSimulatorArm64`; `implementation(:core:datastore:api)` (БД открывает `DataStores.database`). Модулю без своей БД не нужен; тестовая БД в `jvmTest` — плагин `ksp` + `kspJvmTest` (как в `core:datastore:impl`) |

Проверка запрета `impl → impl` в `heartbeat.feature.impl`:

```kotlin
afterEvaluate {
    configurations.filter { it.name.endsWith("Implementation") || it.name.endsWith("Api") }.forEach { conf ->
        conf.dependencies.withType<ProjectDependency>().forEach { dep ->
            val path = dep.path
            require(!(path.startsWith(":features:") && path.endsWith(":impl"))) {
                "${project.path} must not depend on $path (impl -> impl). Depend on the api module instead."
            }
        }
    }
}
```

Корневая задача `detekt` агрегирует все модули: `./gradlew detekt`.

## 2. Version catalog

Добавляй в `gradle/libs.versions.toml` только то, что используется. Пример блока:

```toml
[versions]
decompose = "3.5.0"
flowmvi = "3.2.1"
kstatemachine = "0.38.1"
metro = "1.4.5"
koog = "1.3.0"
napier = "2.7.1"
ktor = "3.6.0"
room = "2.8.5"
sqlite = "2.7.1"
datastore = "1.2.1"
ksp = "2.3.12"
kotlinx-serialization = "1.11.0"
detekt = "2.0.0-alpha.6"
compose-rules = "0.6.7"

[libraries]
decompose = { module = "com.arkivanov.decompose:decompose", version.ref = "decompose" }
decompose-compose = { module = "com.arkivanov.decompose:extensions-compose", version.ref = "decompose" }
flowmvi-core = { module = "pro.respawn.flowmvi:core", version.ref = "flowmvi" }
flowmvi-compose = { module = "pro.respawn.flowmvi:compose", version.ref = "flowmvi" }
flowmvi-essenty = { module = "pro.respawn.flowmvi:essenty", version.ref = "flowmvi" }
flowmvi-test = { module = "pro.respawn.flowmvi:test", version.ref = "flowmvi" }
kstatemachine = { module = "io.github.nsk90:kstatemachine", version.ref = "kstatemachine" }
koog-agents = { module = "ai.koog:koog-agents", version.ref = "koog" }
napier = { module = "io.github.aakira:napier", version.ref = "napier" }
detekt-ktlint-wrapper = { module = "dev.detekt:detekt-rules-ktlint-wrapper", version.ref = "detekt" }
compose-rules-detekt = { module = "io.nlopez.compose.rules:detekt", version.ref = "compose-rules" }

[plugins]
metro = { id = "dev.zacsweers.metro", version.ref = "metro" }
detekt = { id = "dev.detekt", version.ref = "detekt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

Перед добавлением перепроверь последнюю версию на Maven Central и совместимость с Kotlin из каталога.

## 3. Новый модуль

1. Каталог по группе: `core/<name>`, `design-system/<name>`, `features/<name>/{api,impl}`.
2. `build.gradle.kts` — только convention-плагин + специфичные зависимости.
3. `include(...)` в `settings.gradle.kts`.
4. Пакет `io.aequicor.heartbeat.<group>.<name>`.
5. `./gradlew :<path>:compileKotlinJvm` и скилл `verify`.

## 4. Миграция шаблона → целевая раскладка

Порядок (каждый шаг — зелёная сборка):
1. build-logic + detekt (на существующих модулях, создай baseline только если пользователь согласен).
2. `core:logging`, `core:common`, `core:di`.
3. `design-system:tokens`, `design-system:theme` (скилл `design-system`).
4. Остальные `core:*`.
5. `androidApp` → `platform-main/android`, `desktopApp` → `platform-main/desktop`, `shared` → `platform-main/shared` (framework `Shared` для iOS, `iosApp` в Xcode перенастроить на новый путь Gradle-задачи `embedAndSignAppleFrameworkForXcode`).
6. Пакет `io.aequicor` → `io.aequicor.heartbeat` (applicationId не менять без согласования — это id в сторах).
7. Обнови `CLAUDE.md` (раздел «Текущее состояние» и команды).
