---
paths:
  - "features/*/impl/**"
---

# Модуль `features/<name>/impl`

Структура пакета `io.aequicor.heartbeat.feature.<name>.impl`:

```
di/          Metro-контрибуции: @ContributesBinding (Effects), @ContributesIntoSet (RouteEntry / DeepLinkEntry с binding<Profile|AppRouteBinding>()), @ContributesIntoMap (машина в реестр), @ContributesIntoSet (тоглы)
machine/     <Name>EffectsImpl, регистрация машины
data/        репозитории, своя Room-БД (@Database, Entity, DAO, DatabaseSpec — плагин heartbeat.room), мапперы DTO ↔ domain
component/   Decompose-компоненты (ComposableComponent), внутренние Route фичи, вложенные хосты (NavHostFactory)
store/       FlowMVI-сторы (<Screen>State / <Screen>Intent / <Screen>Action + <Screen>Store)
ui/          Compose-экраны (<Screen>Screen), приватные composable, @Preview
```

Правила:
- Зависимости: свой `api`, чужие `api`, `core:*`, `design-system:*`. **Чужой `impl` — никогда.**
- Взаимодействие с другой фичей — `machineRegistry[OtherMachineKey].send(OtherEvent.Public.X)` или `navigator.navigate(OtherRoute)` для навигации (маршрут из её `api`).
- Стор создаётся через `heartbeatStore(name = …)` из `core:mvi` (логирование и обработка ошибок подключены). Стор подписывается на машину, а не хранит её состояние у себя.
- Компонент владеет стором (`retainedStore`/`instanceKeeper`), экран получает только компонент.
- UI — только `Hb*` компоненты и токены `HbTheme`. Строки — Compose Resources.
- Новая функциональность — за тоглом `FeatureToggle` (default `false`, пока фича не готова).
- Эффекты машины: `suspend`, main-safe, результат возвращается событием `<Name>Event.Internal.*`; ошибки → `Internal.Failed`, с логом.
- Данные — только через `@ForScope(AppScope|ProfileScope) DataStores` из `core:datastore` (KV и своя БД). Время жизни записей
  задаётся `Retention` / `RecordRetention` — таймеры и чистки не писать (ADR-0006, скилл `data-storage`).
- Тесты: сторы (FlowMVI test DSL), эффекты (фейки репозиториев), репозитории (in-memory Room / fake `KeyValueStore`).
