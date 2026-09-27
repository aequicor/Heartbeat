# Aequicor Heartbeat

ИИ-студия на Kotlin Multiplatform + Compose Multiplatform.
Платформы: **Android, iOS, Desktop macOS, Desktop Windows** (desktop = один JVM-таргет, выбор UI-кита — в рантайме по ОС).
Корневой пакет: `io.aequicor.heartbeat`. Язык общения в коде/доках: русский в документации, английский в идентификаторах и KDoc.

## Где правила

Источник правды по коду — KDoc в исходниках. Правила для агентов — этот файл и `AGENTS.md`.
В Claude Code правила по путям подгружаются автоматически из `.claude/rules/`; Codex читает применимые правила явно согласно `AGENTS.md`.
Общие процедуры (создать фичу, машину, стор, тогл…) — скиллы в `.claude/skills/`.
Codex обнаруживает одноимённые навыки в `.agents/skills/`; они направляют к этим общим процедурам.

## Группы модулей

```
platform-main/   точки входа: android, desktop, ios (+ shared umbrella/framework);
                 di-bundle — единственный модуль, видящий все impl: Metro-граф (per-platform)
core/            инфраструктура: navigation, mvi, state-machine, di (api/ext/impl), profile-facade (api/impl),
                 resources, datastore, network, ai, feature-toggles, logging, common
design-system/   tokens (pastel), theme, components, layouts, resources, catalog, adaptive (material | fluent | macos)
features/<name>/ api  — контракт: state-machine (состояния, интенты, переходы, эффекты), MachineKey, маршруты
                 impl — UI, FlowMVI-сторы, Decompose-компоненты, репозитории, эффекты машины, DI-контрибуции
build-logic/     convention-плагины Gradle (heartbeat.kmp.library, heartbeat.feature.api/impl, heartbeat.detekt…)
lint/            detekt-rules — собственный набор правил `heartbeat` (политика логирования и обработки ошибок)
```

> Текущее состояние: шаблон перенесён в `platform-main` — `shared` (общий вход, iOS framework `Shared`), `android`, `desktop`,
> Xcode-проект `ios`; пакеты `io.aequicor.heartbeat.platform.*`, applicationId `io.aequicor` сохранён.
> Контракты AI-движков: `features:ai-engine:facade:api` и `authenticator:api`; Pi runtime реализован в pi:api/impl (Desktop, bundled/default); общий facade runtime и UI подключения пока не реализованы.
> Готово: `build-logic` (`heartbeat.detekt`, `heartbeat.kmp.library`, `heartbeat.metro`, `heartbeat.room`), `core:logging`, `core:common`,
> `core:di:{api,ext,impl}`, `core:profile-facade:{api,impl}`, `platform-main:di-bundle` (скоупы app → profile → feature → screen),
> `core:navigation:{api,impl,compose}`, `core:state-machine:{api,impl,flowmvi-ext}`,
> `core:network:{api,impl}`,
> `core:datastore:{api,impl}` (key-value + БД фич, владельцы app/profile, удержание записей),
> `core:secrets:{api,impl}` (защищённые секреты профиля и ссылки), `core:feature-toggles:{api,impl}` (тоглы, реестр, локальные переопределения, `FeatureToggleControl`).
> Дизайн-система: `design-system:{tokens,adaptive,theme,resources,layouts,components,catalog}`;
> отдельная `platform-main:uikit-sandbox:{desktop,android,shared}` и iOS Xcode app — [запуск](platform-main/uikit-sandbox/README.md).
> Приложение: `core:mvi`, фичи `welcome`, `ai-studio`, `toggles-panel`; платформенные входы подключены к root.

## Жёсткие правила (нарушение = блокер ревью)

1. **Зависимости**: `feature:impl` → только `api` других фич. От любого `…:impl` (фич и `core`) зависит только `:platform-main:di-bundle` (проверяет `build-logic` через `heartbeat.detekt`, подключённый ко всем модулям). `core` не знает о `features` и `design-system`. `feature:api` без Compose/UI.
2. **State-machine фичи живёт в `api`** (`machineSpec { }` из `core:state-machine:api`, движок KStateMachine скрыт в `impl`): все состояния, интенты, переходы, эффекты, outputs. Машина запускается в скоупе фичи; другие фичи общаются с ней только через `MachineKey` + `MachineRegistry` → `send(key, Public intent)`. Никаких прямых ссылок на классы `impl`. Исключение для сервисных контрактов и SPI `features:ai-engine` — `.claude/rules/feature-api.md`.
3. **UI-состояние** — FlowMVI-стор в `impl`. Машина = бизнес-флоу фичи, стор = состояние экрана. Стор не дублирует состояние машины, а отражает его (`reflect` из `core:state-machine:flowmvi-ext`).
4. **Навигация** — только Decompose через `core:navigation`: фичи открывают друг друга `Navigator.navigate(Route)`, маршруты — `@Serializable @SerialName` в `api`, `RouteEntry` в реестре своего скоупа (`binding<ProfileRouteBinding>()` / `AppRouteBinding`), результаты — `ResultContract`. Никаких navigation-compose и ссылок на чужие компоненты.
5. **DI** — только Metro (`@Inject`, `@ContributesBinding`, `@ContributesIntoMap/Set`, `@GraphExtension`). Граф — только в `platform-main:di-bundle`; скоупы app → profile → feature → screen, граф фичи — через `retainedGraph` (`core:di:ext`). Никаких сервис-локаторов и `object`-синглтонов с состоянием.
6. **Логирование через `core:logging` (Napier)**: каждое действие пользователя, смена состояния (машины/стора), запрос в сеть, чтение/запись БД/DataStore, изменение конфигурации/тоглов. `println`, `android.util.Log`, `NSLog` запрещены. Секреты и API-ключи не логируются никогда. Проверяется detekt (набор `heartbeat`, `lint/detekt-rules`).
7. **Цвета/типографика/отступы — только токены `design-system`**. `Color(0x…)`, `.sp`/`.dp`-литералы для стилей вне `design-system` запрещены.
8. **Тоглы** — через `core:feature-toggles` (`FeatureToggles` для чтения, регистрация `@IntoSet`); новая функциональность за тоглом по умолчанию.
9. **Корутины**: без `GlobalScope`, `runBlocking` в продовом коде; диспетчеры инжектятся (`DispatcherProvider`); `CancellationException` не глотаем.
   **Ошибки**: никакая ошибка не игнорируется — минимум `log.w(e)`/`log.e(e)` с throwable или проброс (rethrow / `Result.failure(e)`); исключение — `CancellationException`, она пробрасывается. `@Suppress` этих правил запрещён (`ForbiddenSuppress`).
10. **Detekt + compose-rules + ktlint** обязаны быть зелёными. `@Suppress` — только с комментарием-причиной.
11. Версии библиотек приложения — только в `gradle/libs.versions.toml`. Python-инструмент проверки коммитов закреплён отдельно (`scripts/requirements-commit-policy.txt`).
12. **PR содержит законченную фичу; коммиты — отдельные логические шаги.** Каждый коммит должен оставлять проект собираемым, тесты изменённого поведения идут вместе с реализацией. Бюджет каждого коммита — **не более 25 000 токенов полного diff**; свыше 20 000 токенов или 20 файлов — предупреждение. Ориентир — 700–1000 добавленных и удалённых строк суммарно, без жёсткого лимита строк и размера PR. Команды — скилл `verify`.

## Команды

Windows: `.\gradlew.bat`, macOS: `./gradlew`.

```
./gradlew :platform-main:desktop:run           # desktop
./gradlew :platform-main:android:assembleDebug # android
./gradlew allTests                             # все KMP-тесты
./gradlew jvmTest                              # быстрые тесты всех KMP-модулей (JVM)
./gradlew :platform-main:di-bundle:jvmTest     # сборка всего Metro-графа + интеграционные тесты скоупов
./gradlew detekt                               # lint всех модулей (без type resolution, быстро)
./gradlew detekt --auto-correct                # автоформат ktlint-правил
./gradlew :<module>:detektMainJvm              # detekt с type resolution (KMP jvm; :platform-main:desktop:detektMain для JVM-модулей)
./gradlew :lint:detekt-rules:test              # тесты собственных правил detekt
./gradlew --stop                               # после правки lint/detekt-rules: detekt кэширует classloader правил в демоне
./gradlew :<module>:dependencies --configuration commonMainApi   # проверить граф
```

iOS собирается только на macOS (Xcode, `platform-main/ios/`). На Windows iOS-таргеты не компилируются — проверяй `commonMain` через `jvmTest`.

## Как работать

- Перед изменением фичи прочитай её `api` (машину) — это спецификация поведения.
- Планируй логические коммиты до реализации; перед каждым коммитом проверяй staged diff через `python scripts/check_commit_size.py --staged`, перед ревью — весь диапазон PR через `--base <base-ref> --head HEAD`. Промежуточные fixup-коммиты объединяй с соответствующим логическим шагом перед ревью.
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
- Не добавляй библиотеки вне `gradle/libs.versions.toml` без согласования с пользователем.
- Документацию пиши в KDoc; правила для агентов — в `CLAUDE.md` / `AGENTS.md` (и подключаемых из них `.claude/rules/`, скиллах). Папку `docs/` не создавай.
