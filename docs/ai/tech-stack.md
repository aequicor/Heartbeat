# Технологический стек

Версии проверены по Maven Central / Google Maven на 2026-09-26. Базовые версии проекта — в `gradle/libs.versions.toml`
(Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.1.1, coroutines 1.11.0). При добавлении библиотеки в каталог
перепроверь актуальную версию и совместимость с Kotlin.

| Назначение | Библиотека | Координаты | Версия | Документация |
|---|---|---|---|---|
| Навигация | Decompose + Essenty | `com.arkivanov.decompose:decompose`, `:extensions-compose`, `:extensions-compose-experimental` (`ChildStack`/`ChildPanels` со `StackAnimationScope` — shared elements + predictive back; experimental, ADR-0003); Essenty `com.arkivanov.essenty:instance-keeper`, `:state-keeper` (в `core:di:ext`) | 3.5.0 (3.6.0-beta01); Essenty 2.5.0 (= Decompose 3.5.0) | https://arkivanov.github.io/Decompose/ |
| MVI | FlowMVI | `pro.respawn.flowmvi:core`, `:compose`, `:essenty`, `:essenty-compose`, `:test` | 3.2.1 | https://opensource.respawn.pro/FlowMVI/ |
| State-machine | KStateMachine | `io.github.nsk90:kstatemachine`, `:kstatemachine-coroutines` | 0.38.1 | https://kstatemachine.github.io/kstatemachine/ |
| DI | Metro | плагин `dev.zacsweers.metro` (runtime добавляется сам), через `heartbeat.metro` (`generateContributionProviders = true`) | 1.4.5 (поддерживает Kotlin 2.4.20 с 1.2.0) | https://zacsweers.github.io/metro/ |
| Ресурсы | Compose Resources | `org.jetbrains.compose.components:components-resources` | = Compose | https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources.html |
| БД | Room KMP | `androidx.room:room-runtime`, `room-compiler` (KSP), плагин `androidx.room` | 2.8.5 | https://developer.android.com/kotlin/multiplatform/room |
| SQLite-драйвер | sqlite-bundled | `androidx.sqlite:sqlite-bundled` | 2.7.1 | ↑ |
| KSP | KSP2 | плагин `com.google.devtools.ksp` | 2.3.12 | https://github.com/google/ksp |
| Настройки | DataStore KMP | `androidx.datastore:datastore-preferences-core` | 1.2.1 (1.3.0-alpha) | https://developer.android.com/kotlin/multiplatform/datastore |
| Сеть | Ktor client | `io.ktor:ktor-client-core`, `-content-negotiation`, `-serialization-kotlinx-json`, engines `-okhttp` (android/jvm), `-darwin` (ios), `-mock` (тесты); `-logging` не используется — HTTP логирует свой плагин `core:network:impl` (ADR-0005) | 3.6.0 | https://ktor.io/docs/client-create-new-application.html |
| Сериализация | kotlinx.serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` + плагин `org.jetbrains.kotlin.plugin.serialization` | 1.11.0 | — |
| ИИ-агенты | Koog | `ai.koog:koog-agents` (есть android, jvm, ios варианты) | 1.3.0 | https://docs.koog.ai/ |
| Логирование | Napier | `io.github.aakira:napier` | 2.7.1 | https://github.com/AAkira/Napier |
| Lint | detekt 2 | плагин `dev.detekt`, `dev.detekt:detekt-rules-ktlint-wrapper` | 2.0.0-alpha.6 | https://detekt.dev/docs/intro |
| Compose lint | compose-rules | `io.nlopez.compose.rules:detekt` | 0.6.7 | https://mrmans0n.github.io/compose-rules/latest/detekt/ |
| Immutable | kotlinx-collections-immutable | `org.jetbrains.kotlinx:kotlinx-collections-immutable` | 0.5.2 | — |
| Тесты | coroutines-test, Turbine | `org.jetbrains.kotlinx:kotlinx-coroutines-test`, `app.cash.turbine:turbine` | 1.11.0, 1.2.1 | — |
| UI Windows | compose-fluent-ui | `io.github.compose-fluent:fluent` (+ `fluent-icons-extended`) | `v0.1.0` (версия с префиксом `v`!) | https://github.com/compose-fluent/compose-fluent-ui |
| UI macOS | compose-macos-26-ui | `dev.nucleusframework:compose-macos-ui` (+ `-icons-extended`, `-markdown`) | 1.1.0 | https://github.com/NucleusFramework/compose-macos-26-ui |

## Важные замечания по совместимости

- **detekt**: стабильная 1.23.8 не поддерживает синтаксис Kotlin 2.4 → используем 2.0.0-alpha.6 (новый id плагина `dev.detekt`,
  ruleset ktlint называется `ktlint`, а не `formatting`). compose-rules 0.6.3+ ↔ detekt 2.0.0-alpha.6 ↔ Kotlin 2.4.10+.
  Конфиг: [config/detekt/detekt.yml](../../config/detekt/detekt.yml) (проверен против default-config 2.0.0-alpha.6).
  Собственные правила — `:lint:detekt-rules` (`dev.detekt:detekt-api`, тесты `dev.detekt:detekt-test` + JUnit Jupiter 6.1.3),
  подключаются плагином `heartbeat.detekt` как `detektPlugins`. Плагин detekt кэширует classloader правил в Gradle-демоне по пути jar —
  после изменения правил нужен `./gradlew --stop`, иначе `NoClassDefFoundError`.
- **Metro**: compiler plugin, привязан к версии Kotlin — при апгрейде Kotlin сначала проверь релиз Metro.
- **Room**: KSP нужно подключать для каждого таргета (`kspAndroid`, `kspJvm`, `kspIosArm64`, `kspIosSimulatorArm64`).
- **FlowMVI 3.3.0** пока alpha — остаёмся на 3.2.x.
- **Koog** требует JDK 17+ на JVM. Ключи провайдеров не храним в коде.
- **Fluent** публикуется с версией `v0.1.0` (буква `v` — часть версии).
- **macOS-кит** мультиплатформенный, но подключаем его только в `jvmMain` модуля `design-system:adaptive`.

## Ключевые пакеты (для импортов)

| Библиотека | Пакет |
|---|---|
| Decompose | `com.arkivanov.decompose`, `com.arkivanov.decompose.router.stack`, `com.arkivanov.decompose.extensions.compose.stack` |
| Essenty | `com.arkivanov.essenty.lifecycle`, `com.arkivanov.essenty.instancekeeper` |
| FlowMVI | `pro.respawn.flowmvi.api`, `pro.respawn.flowmvi.dsl`, `pro.respawn.flowmvi.plugins`, `pro.respawn.flowmvi.logging`, `pro.respawn.flowmvi.compose.dsl`, `pro.respawn.flowmvi.essenty.dsl` |
| KStateMachine | `ru.nsk.kstatemachine.statemachine`, `.state`, `.event`, `.transition`, `.coroutines` |
| Metro | `dev.zacsweers.metro` |
| Koog | `ai.koog.agents.core.agent`, `ai.koog.agents.core.tools`, `ai.koog.prompt.executor.llms.all`, `ai.koog.agents.features.eventHandler.feature` |
| Napier | `io.github.aakira.napier` (только в `core:logging`) |
| Fluent | `io.github.composefluent`, `io.github.composefluent.component` |
| macOS | `dev.nucleusframework.macoscompose.theme`, `dev.nucleusframework.macoscompose.components` |
