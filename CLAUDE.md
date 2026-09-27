# Aequicor Heartbeat

ИИ-студия на Kotlin Multiplatform + Compose Multiplatform.
Платформы: **Android, iOS, Desktop macOS, Desktop Windows** (desktop = один JVM-таргет, выбор UI-кита — в рантайме по ОС).
Корневой пакет: `io.aequicor.heartbeat`. Язык общения в коде/доках: русский в документации, английский в идентификаторах и KDoc.

## Карта знаний (читать по необходимости, не целиком)

| Тема | Где |
|---|---|
| Архитектура, группы модулей, правила зависимостей | [docs/ai/architecture.md](docs/ai/architecture.md) |
| Контракт фичи: state-machine в `api`, связь машин через object-key | [docs/ai/feature-contract.md](docs/ai/feature-contract.md) |
| Политика логирования (обязательна) | [docs/ai/logging-policy.md](docs/ai/logging-policy.md) |
| Дизайн-система Mission, токены, платформенные киты | [docs/ai/design-system.md](docs/ai/design-system.md) |
| Стек и версии библиотек, ссылки на доки | [docs/ai/tech-stack.md](docs/ai/tech-stack.md) |
| Архитектурные решения (ADR) | [docs/adr/](docs/adr/) |

Правила по путям подгружаются автоматически из `.claude/rules/`. Процедуры (создать фичу, машину, стор, тогл…) — скиллы в `.claude/skills/`.

## Группы модулей

```
platform-main/   точки входа: android, desktop, ios (+ shared umbrella/framework);
                 di-bundle — единственный модуль, видящий все impl: Metro-граф (per-platform)
core/            инфраструктура: navigation, mvi, state-machine, di (api/ext/impl), profile-facade (api/impl),
                 resources, datastore, network, ai, feature-toggles, logging, common
design-system/   tokens (Mission), theme, components, adaptive (material | fluent | macos)
features/<name>/ api  — контракт: state-machine (состояния, интенты, переходы, эффекты), MachineKey, маршруты
                 impl — UI, FlowMVI-сторы, Decompose-компоненты, репозитории, эффекты машины, DI-контрибуции
build-logic/     convention-плагины Gradle (heartbeat.kmp.library, heartbeat.feature.api/impl, heartbeat.detekt…)
lint/            detekt-rules — собственный набор правил `heartbeat` (политика логирования и обработки ошибок)
```

> Текущее состояние: шаблонные модули (`androidApp`, `desktopApp`, `iosApp`, `shared`) ещё не перенесены.
> Готово: `build-logic` (`heartbeat.detekt`, `heartbeat.kmp.library`, `heartbeat.metro`, `heartbeat.room`), `core:logging`, `core:common`,
> `core:di:{api,ext,impl}`, `core:profile-facade:{api,impl}`, `platform-main:di-bundle` (скоупы — [ADR-0002](docs/adr/0002-di-scopes.md)),
> `core:navigation:{api,impl,compose}` ([ADR-0003](docs/adr/0003-navigation.md)), `core:state-machine:{api,impl,flowmvi-ext}` ([ADR-0004](docs/adr/0004-state-machine.md)),
> `core:network:{api,impl}` ([ADR-0005](docs/adr/0005-network.md)),
> `core:datastore:{api,impl}` (key-value + БД фич, владельцы app/profile, удержание записей — [ADR-0006](docs/adr/0006-datastore.md)),
> `core:feature-toggles:{api,impl}` (тоглы, реестр, локальные переопределения, `FeatureToggleControl` — [ADR-0007](docs/adr/0007-feature-toggles.md)).
> Дальше — по [docs/ai/architecture.md](docs/ai/architecture.md#миграция-из-шаблона).

## Жёсткие правила (нарушение = блокер ревью)

1. **Зависимости**: `feature:impl` → только `api` других фич. От любого `…:impl` (фич и `core`) зависит только `:platform-main:di-bundle` (проверяет `build-logic` через `heartbeat.detekt`, подключённый ко всем модулям). `core` не знает о `features` и `design-system`. `feature:api` без Compose/UI.
2. **State-machine фичи живёт в `api`** (`machineSpec { }` из `core:state-machine:api`, движок KStateMachine скрыт в `impl`): все состояния, интенты, переходы, эффекты, outputs. Машина запускается в скоупе фичи; другие фичи общаются с ней только через `MachineKey` + `MachineRegistry` → `send(key, Public intent)` ([ADR-0004](docs/adr/0004-state-machine.md)). Никаких прямых ссылок на классы `impl`.
3. **UI-состояние** — FlowMVI-стор в `impl`. Машина = бизнес-флоу фичи, стор = состояние экрана. Стор не дублирует состояние машины, а отражает его (`reflect` из `core:state-machine:flowmvi-ext`).
4. **Навигация** — только Decompose через `core:navigation` ([ADR-0003](docs/adr/0003-navigation.md)): фичи открывают друг друга `Navigator.navigate(Route)`, маршруты — `@Serializable @SerialName` в `api`, `RouteEntry` в реестре своего скоупа (`binding<ProfileRouteBinding>()` / `AppRouteBinding`), результаты — `ResultContract`. Никаких navigation-compose и ссылок на чужие компоненты.
5. **DI** — только Metro (`@Inject`, `@ContributesBinding`, `@ContributesIntoMap/Set`, `@GraphExtension`). Граф — только в `platform-main:di-bundle`; скоупы app → profile → feature → screen, граф фичи — через `retainedGraph` (`core:di:ext`). Никаких сервис-локаторов и `object`-синглтонов с состоянием.
6. **Логирование через `core:logging` (Napier)**: каждое действие пользователя, смена состояния (машины/стора), запрос в сеть, чтение/запись БД/DataStore, изменение конфигурации/тоглов. `println`, `android.util.Log`, `NSLog` запрещены. Секреты и API-ключи не логируются никогда. Проверяется detekt (набор `heartbeat`, см. [logging-policy.md](docs/ai/logging-policy.md#автоматическая-проверка-detekt)).
7. **Цвета/типографика/отступы — только токены `design-system`**. `Color(0x…)`, `.sp`/`.dp`-литералы для стилей вне `design-system` запрещены.
8. **Тоглы** — через `core:feature-toggles` (`FeatureToggles` для чтения, регистрация `@IntoSet`); новая функциональность за тоглом по умолчанию.
9. **Корутины**: без `GlobalScope`, `runBlocking` в продовом коде; диспетчеры инжектятся (`DispatcherProvider`); `CancellationException` не глотаем.
   **Ошибки**: никакая ошибка не игнорируется — минимум `log.w(e)`/`log.e(e)` с throwable или проброс (rethrow / `Result.failure(e)`); исключение — `CancellationException`, она пробрасывается. `@Suppress` этих правил запрещён (`ForbiddenSuppress`).
10. **Detekt + compose-rules + ktlint** обязаны быть зелёными. `@Suppress` — только с комментарием-причиной.
11. Версии библиотек — только в `gradle/libs.versions.toml`.

## Команды

Windows: `.\gradlew.bat`, macOS: `./gradlew`.

```
./gradlew :desktopApp:run                      # desktop
./gradlew :androidApp:assembleDebug            # android
./gradlew allTests                             # все KMP-тесты
./gradlew :shared:jvmTest                      # быстрые тесты (JVM)
./gradlew :platform-main:di-bundle:jvmTest     # сборка всего Metro-графа + интеграционные тесты скоупов
./gradlew detekt                               # lint всех модулей (без type resolution, быстро)
./gradlew detekt --auto-correct                # автоформат ktlint-правил
./gradlew :shared:detektMainJvm                # detekt с type resolution (KMP jvm; :<app>:detektMain для JVM-модулей)
./gradlew :lint:detekt-rules:test              # тесты собственных правил detekt
./gradlew --stop                               # после правки lint/detekt-rules: detekt кэширует classloader правил в демоне
./gradlew :<module>:dependencies --configuration commonMainApi   # проверить граф
```

iOS собирается только на macOS (Xcode, `iosApp/`). На Windows iOS-таргеты не компилируются — проверяй `commonMain` через `jvmTest`.

## Как работать

- Перед изменением фичи прочитай её `api` (машину) — это спецификация поведения.
- После правок кода → скилл `verify` (сборка, тесты, detekt). Для крупных изменений — субагент `architecture-reviewer`.

| Задача | Скилл (`.claude/skills/`) |
|---|---|
| Новая фича целиком | `new-feature` |
| Машина, события, переходы, межфичевое взаимодействие | `state-machine` |
| Состояние экрана | `mvi-store` |
| Экраны, переходы, root, точки входа | `navigation` |
| Внедрение зависимостей | `di-metro` |
| UI-компоненты, токены, киты платформ | `design-system` |
| Тоглы | `feature-toggle` |
| Room / DataStore / репозитории | `data-storage` |
| HTTP | `network` |
| Агенты, инструменты, LLM-провайдеры | `ai-koog` |
| Логирование, аудит логов | `logging` |
| Gradle, build-logic, новый модуль, миграция шаблона | `module-setup` |
| Проверка перед «готово» | `verify` |

Субагенты (`.claude/agents/`): `feature-architect` (дизайн фичи до кода), `architecture-reviewer`, `ui-reviewer`, `test-writer`, `build-doctor`.
Хук `.claude/hooks/check-conventions.sh` проверяет каждый изменённый `.kt`/`.kts` (логи, корутины, цвета, границы модулей) и возвращает нарушения — исправляй сразу.
- Не добавляй библиотеки вне [tech-stack.md](docs/ai/tech-stack.md) без ADR.
- Значимое архитектурное решение → новый ADR в `docs/adr/` (шаблон `0000-template.md`).
