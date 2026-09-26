# Архитектура Heartbeat

## Слои и группы модулей

```
                     ┌──────────────────────────────────────────┐
                     │ platform-main: android / desktop / ios   │  DI-граф приложения, root-компонент,
                     │               shared (umbrella)          │  выбор UI-кита, инициализация логов
                     └───────────────┬──────────────────────────┘
                                     │ зависит от всех impl
                ┌────────────────────┼─────────────────────┐
                ▼                    ▼                     ▼
       features/<a>/impl    features/<b>/impl  ...   design-system/*
          │      │  └──────────┐      │                    │
          │      ▼             ▼      ▼                    │
          │  features/<a>/api  features/<b>/api            │
          │         │                 │                    │
          ▼         ▼                 ▼                    ▼
       ┌───────────────────────── core/* ─────────────────────────┐
       │ logging ← common ← di, state-machine, mvi, navigation,   │
       │ network, database, datastore, feature-toggles, ai,       │
       │ resources                                                │
       └──────────────────────────────────────────────────────────┘
```

### Разрешённые зависимости

| Модуль | Может зависеть от | Не может |
|---|---|---|
| `platform-main:di-bundle` | всё, включая `impl` фич и `core:*:impl` — единственное место сборки Metro-графа | — |
| `platform-main:root` | `di-bundle` (`HeartbeatRoot`), `core:navigation:compose` — Compose-корень `RootContent` | любой `impl` напрямую |
| `platform-main:android/desktop/shared` | `di-bundle`, `root`, `design-system:*`, `core:*` (api) | любой `impl` напрямую |
| `features:X:impl` | `features:X:api`, `features:*:api`, `core:*`, `design-system:*` | `features:*:impl` |
| `features:X:api` | `core:state-machine`, `core:navigation:api`, `core:common`, `core:feature-toggles` (api) | Compose UI, `design-system`, любой `impl`, `core:database/network/ai` |
| `design-system:*` | `core:resources`, `core:logging`, `core:common`, UI-киты | `features:*`, остальной `core` |
| `core:X` | `core:logging`, `core:common`, другие `core` без циклов | `features:*`, `design-system:*`, любой `impl` |
| `core:X:impl` (`di`, `profile-facade`) | свой `api`, другие `core` | — ; от него зависит только `di-bundle` |
| `core:logging` | Napier | всё прочее в проекте |

`api`-модули экспортируют (`api(...)`) только то, что входит в их публичный контракт; всё остальное — `implementation`.

### Пакеты

`io.aequicor.heartbeat.<group>.<module>[.<sub>]`

- `io.aequicor.heartbeat.core.network`
- `io.aequicor.heartbeat.feature.chat.api` / `io.aequicor.heartbeat.feature.chat.impl`
- `io.aequicor.heartbeat.ds.tokens`, `io.aequicor.heartbeat.ds.components`
- `io.aequicor.heartbeat.platform.android`

Gradle-пути: `:core:network`, `:features:chat:api`, `:features:chat:impl`, `:design-system:tokens`, `:platform-main:android`.

## Core-модули

| Модуль | Ответственность | Библиотека |
|---|---|---|
| `core:logging` | фасад `Log`, инициализация Napier, теги, редактирование секретов, адаптеры логгеров для FlowMVI/Ktor/KStateMachine/Koog | Napier |
| `core:common` | `DispatcherProvider` (+ Main-диспетчеры платформ), `PlatformInfo`, Result/ошибки, Clock | coroutines |
| `core:di:api` | скоупы (`ProfileScope`, `@ForScope`), `ScopeHandle`, `ScopeSavedState`, `ScopeFactory`, shared-скоупы | Metro, kotlinx-serialization (api) |
| `core:di:ext` | `retainedGraph` / `retainedScope` / `retainedShared` — скоуп, привязанный к компоненту | Essenty |
| `core:di:impl` | жизненный цикл скоупов (каскадное закрытие, saved state), корневой app-скоуп | Metro |
| `core:profile-facade:api` | `ProfileSessions`, `ProfileId`, `ActiveProfileStorage`, `ProfileGraph` (граф `ProfileScope`) | Metro |
| `core:profile-facade:impl` | сессии профиля: открыть / переключить / закрыть / восстановить | — |
| `core:state-machine` | `MachineKey<E>`, `MachineRef<E>`, `MachineRegistry`, логирующий listener, тест-утилиты | KStateMachine |
| `core:mvi` | базовая конфигурация стора (`heartbeatStore { }`), логирующий плагин, обработка ошибок | FlowMVI |
| `core:navigation:api` | `Route`, `RouteEntry`, `Navigator`, `NavOptions`, `ResultContract`, хосты (`StackHost`/`PanelsHost`/`RootHost`), `NavHostFactory`, `DeepLinkEntry` — без Compose | Decompose |
| `core:navigation:impl` | хосты на `childStack`/`childPanels`, реестры маршрутов (App/Profile), результаты, deep links, логи `NAV` | Decompose |
| `core:navigation:compose` | `ComposableComponent`, `NavStack`, `NavPanels`, анимации, shared-element «раскрытие из превью» | Decompose extensions-compose(-experimental) |
| `core:resources` | общие строки/иконки/шрифты, локализация | Compose Resources |
| `core:database` | `HeartbeatDatabase`, драйвер, миграции, фабрики per-platform | Room KMP + BundledSQLiteDriver |
| `core:datastore` | фабрика `DataStore<Preferences>` per-platform, логирующая обёртка | DataStore KMP |
| `core:network` | `HttpClient` c engine per-platform, JSON, ретраи, логирование | Ktor 3 |
| `core:ai` | провайдеры LLM, `PromptExecutor`, реестр инструментов, агенты, ключи из безопасного хранилища | Koog |
| `core:feature-toggles` | `FeatureToggle<T>`, `FeatureToggles` (Flow), хранение в DataStore, реестр | DataStore |

## DI и скоупы

Подробно — [ADR-0002](../adr/0002-di-scopes.md) и скилл `di-metro`.

```
AppScope        HeartbeatGraph   platform-main:di-bundle (per-platform @DependencyGraph)
└ ProfileScope  ProfileGraph     core:profile-facade:api
  ├ <Feature>Scope  <Feature>Graph  features/<x>/impl, фабрика @ContributesTo(ProfileScope::class)
  │ └ screen    retainedScope()  без графа, InstanceKeeper компонента
  └ shared:<key>  SharedScopes   ref-counted объект, общий для нескольких фич
```

- Закрытие скоупа каскадно закрывает дочерние; корутины скоупа отменяются.
- После смерти процесса граф пересобирается из аргументов (профиль — `ActiveProfileStorage`, фича/экран — конфиг
  Decompose), состояние объектов скоупа — из `ScopeSavedState`.

## Контракт фичи

См. [feature-contract.md](feature-contract.md). Коротко:

- `api`: `sealed interface <Name>State`, `sealed interface <Name>Event`, `object <Name>MachineKey : MachineKey<<Name>Event>`, функция-спека машины, интерфейс эффектов, публичные `Route` и `ResultContract` (если фича открывается извне; см. [ADR-0003](../adr/0003-navigation.md)).
- `impl`: реализация эффектов (репозитории, сеть, ИИ), FlowMVI-сторы, Decompose-компоненты, Compose-экраны, Metro-контрибуции (`@ContributesBinding`, `@ContributesIntoMap` машины в реестр, `@ContributesIntoSet` тоглов).

## Потоки данных

```
UI (Compose) ──intent──▶ FlowMVI Store ──event──▶ Feature StateMachine ──onEntry──▶ Effects (impl)
     ▲                       │   ▲                        │                         │
     └──────state────────────┘   └──── machine state ─────┘            repos/network/ai/db
                                                          │
                          другие машины ◀── MachineRegistry[Key].send(Event)
```

- Store читает состояние машины (`StateFlow`) и маппит в UI-state.
- Бизнес-переходы — только событиями машины. Store не принимает бизнес-решений, которые меняют флоу фичи.
- Эффекты машины (загрузка, вызов агента) запускаются в `onEntry` через интерфейс эффектов, результат возвращается событием.

## Платформы и UI-киты

| Платформа | Кит | Как выбирается |
|---|---|---|
| Android | Material 3 + токены Mission | `androidMain` |
| iOS | Material 3 + токены Mission (iOS-адаптация отступов/жестов) | `iosMain` |
| Windows | compose-fluent-ui | рантайм: `System.getProperty("os.name")` в `jvmMain` |
| macOS | compose-macos-26-ui | рантайм там же |

Фичи используют **только** компоненты `design-system:components` (`Hb*`). Выбор реализации — через `LocalPlatformUi` в `design-system:adaptive`.

## Миграция из шаблона

Шаблон содержит `androidApp`, `desktopApp`, `iosApp`, `shared`. Порядок миграции:

1. `build-logic/` с convention-плагинами (KMP-таргеты, detekt, compose, Metro) — сначала.
2. `core:logging`, `core:common`, `core:di:{api,ext,impl}`, `core:profile-facade:{api,impl}`, `platform-main:di-bundle` — фундамент (сделано, ADR-0002).
3. `design-system:tokens`, `design-system:theme`.
4. Остальные `core:*`.
5. Перенос `androidApp` → `platform-main:android`, `desktopApp` → `platform-main:desktop`, `shared` → `platform-main:shared`, `iosApp` остаётся Xcode-проектом, подключающим framework из `platform-main:shared`.
6. Первая фича по скиллу `new-feature`.
