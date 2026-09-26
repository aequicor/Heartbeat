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
| `platform-main:*` | всё | — |
| `features:X:impl` | `features:X:api`, `features:*:api`, `core:*`, `design-system:*` | `features:*:impl` |
| `features:X:api` | `core:state-machine`, `core:navigation` (только типы), `core:common`, `core:feature-toggles` (api) | Compose UI, `design-system`, любой `impl`, `core:database/network/ai` |
| `design-system:*` | `core:resources`, `core:logging`, `core:common`, UI-киты | `features:*`, остальной `core` |
| `core:X` | `core:logging`, `core:common`, другие `core` без циклов | `features:*`, `design-system:*` |
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
| `core:common` | `DispatcherProvider`, `AppScope`-корутины, Result/ошибки, Clock | coroutines |
| `core:di` | `AppScope`, общие квалификаторы, базовые `@ContributesTo`-интерфейсы | Metro |
| `core:state-machine` | `MachineKey<E>`, `MachineRef<E>`, `MachineRegistry`, логирующий listener, тест-утилиты | KStateMachine |
| `core:mvi` | базовая конфигурация стора (`heartbeatStore { }`), логирующий плагин, обработка ошибок | FlowMVI |
| `core:navigation` | root-конфиги, утилиты `childStack`, логирование навигации, deep-link-модель | Decompose / Essenty |
| `core:resources` | общие строки/иконки/шрифты, локализация | Compose Resources |
| `core:database` | `HeartbeatDatabase`, драйвер, миграции, фабрики per-platform | Room KMP + BundledSQLiteDriver |
| `core:datastore` | фабрика `DataStore<Preferences>` per-platform, логирующая обёртка | DataStore KMP |
| `core:network` | `HttpClient` c engine per-platform, JSON, ретраи, логирование | Ktor 3 |
| `core:ai` | провайдеры LLM, `PromptExecutor`, реестр инструментов, агенты, ключи из безопасного хранилища | Koog |
| `core:feature-toggles` | `FeatureToggle<T>`, `FeatureToggles` (Flow), хранение в DataStore, реестр | DataStore |

## Контракт фичи

См. [feature-contract.md](feature-contract.md). Коротко:

- `api`: `sealed interface <Name>State`, `sealed interface <Name>Event`, `object <Name>MachineKey : MachineKey<<Name>Event>`, функция-спека машины, интерфейс эффектов, фабрика корневого компонента, конфиги навигации (если фича открывается извне).
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
2. `core:logging`, `core:common`, `core:di` — фундамент.
3. `design-system:tokens`, `design-system:theme`.
4. Остальные `core:*`.
5. Перенос `androidApp` → `platform-main:android`, `desktopApp` → `platform-main:desktop`, `shared` → `platform-main:shared`, `iosApp` остаётся Xcode-проектом, подключающим framework из `platform-main:shared`.
6. Первая фича по скиллу `new-feature`.
