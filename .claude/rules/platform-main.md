---
paths:
  - "platform-main/**"
  - "androidApp/**"
  - "desktopApp/**"
  - "iosApp/**"
  - "shared/**"
---

# Точки входа (`platform-main`)

- Metro-граф — только в `platform-main:di-bundle`: `HeartbeatGraph` в `commonMain` без аннотации + `@DependencyGraph(AppScope::class)` в каждом платформенном source set'е (`AndroidHeartbeatGraph`, `JvmHeartbeatGraph`, `IosHeartbeatGraph`) — иначе Metro не видит платформенные контрибуции. Точки входа вызывают `createHeartbeatGraph(...)`.
- `di-bundle` — единственный модуль, который зависит от `…:impl` (фич и `core:*:impl`). Точки входа `android`/`desktop`/`shared` зависят от `di-bundle`, не от `impl`.
- Точки входа: создать `HeartbeatRoot(context, graph, RootStart(...))` (di-bundle, один раз на Activity/окно/UIViewController), передавать ему deep links (`handleDeepLink`), `Log.init(isDebug)`, выбор UI-кита (`LocalPlatformUi`), рендер — `RootContent(root)` из `platform-main:root`. Восстановление профиля, переключение гость/профиль и отложенные deep links root делает сам (скилл `navigation`, раздел 9).
- Доступ root к профильным entry point'ам — интерфейс-аксессор `@ContributesTo(ProfileScope::class)` и `session.graph as <Accessors>`.
- Порядок старта: `Log.init` → `createHeartbeatGraph()` → root `ComponentContext` (с `LifecycleRegistry`) → `HbTheme { RootContent(root) }`.
- Android: `defaultComponentContext()` в `Activity`; не держи ссылки на `Activity` в графе.
- Desktop: `LifecycleController` + `runOnUiThread` для создания root-компонента; определение ОС → `PlatformUi.Fluent` (Windows) / `PlatformUi.MacOs` (macOS) / `Material` (прочие).
- iOS: `MainViewController()` в `platform-main:shared` (iosMain), lifecycle из `ApplicationLifecycle`; Swift-код в `iosApp/` — минимальный.
- Никакой бизнес-логики и UI фич — только сборка.
- Шаблонные модули `androidApp`/`desktopApp`/`shared` — переносятся в `platform-main` (см. `docs/ai/architecture.md#миграция-из-шаблона`).
