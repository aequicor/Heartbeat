---
paths:
  - "features/**/impl/**"
---

# Модуль `features/<name>/impl`

Структура пакета `io.aequicor.heartbeat.feature.<name>.impl`:

```
di/          Metro-контрибуции: граф фичи, <Name>MachineBindings (@Provides машины через MachineLauncher в <Feature>Scope), @ContributesIntoSet (RouteEntry / DeepLinkEntry с binding<Profile|AppRouteBinding>()), @ContributesTo + @Provides @IntoSet (тоглы: тип результата ровно FeatureToggle<*>)
machine/     <Name>EffectHandler : EffectHandler<Effect, Intent> (@ContributesBinding(<Feature>Scope::class))
data/        репозитории, своя Room-БД (@Database, Entity, DAO, DatabaseSpec — плагин heartbeat.room), DAO-адаптеры, мапперы DTO ↔ domain
component/   Decompose-компоненты (ComposableComponent), внутренние Route фичи, вложенные хосты (NavHostFactory)
store/       FlowMVI-сторы (<Screen>State / <Screen>Intent / <Screen>Action + <Screen>Store)
ui/          Compose-экраны (<Screen>Screen), приватные composable, @Preview
```

Правила:
- Зависимости: свой `api`, чужие `api`, `core:*`, `design-system:*`. **Чужой `impl` — никогда.**
- Взаимодействие с другой фичей — `machineRegistry.send(OtherMachineKey, OtherIntent.Public.X)` (`NotRunning`, если фича закрыта) или `navigator.navigate(OtherRoute)` для навигации (маршрут из её `api`).
- Машина фичи живёт в скоупе фичи: `@Provides @SingleIn(<Feature>Scope::class)` + `MachineLauncher.launch(spec, scope, effects)`. KStateMachine напрямую не используется.
- Стор создаётся через `heartbeatStore(name = …)` из `core:mvi` (логирование и обработка ошибок подключены). Стор отражает машину через `reflect(machine) { when … }` и шлёт интенты через `sendTo` (`core:state-machine:flowmvi-ext`), а не хранит её состояние у себя.
- Компонент владеет стором (`retainedStore`/`instanceKeeper`), экран получает только компонент.
- UI — только `Hb*` компоненты и токены `HbTheme`. Строки — Compose Resources.
- Новая функциональность — за тоглом `FeatureToggle` (default `false`, пока фича не готова).
- Эффекты машины: `EffectHandler.handle` — `suspend`, main-safe, результат — `machine.send(<Name>Intent.Internal.*)`; исключение логирует рантайм и мапит `onEffectFailure` → `Internal.Failed`. Жизненный цикл эффекта уже в логе `SM/<name>` — логируй только детали IO.
- Данные — только через `@ForScope(AppScope|ProfileScope) DataStores` из `core:datastore` (KV и своя БД). Время жизни записей
  задаётся `Retention` / `RecordRetention` — таймеры и чистки не писать (скилл `data-storage`).
- Тесты: сторы (FlowMVI test DSL, фейк `Machine`), `EffectHandler` (фейк `EffectScope` + фейки репозиториев), репозитории (in-memory Room / fake `KeyValueStore` / `DataStores`).
