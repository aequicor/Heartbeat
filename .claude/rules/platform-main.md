---
paths:
  - "platform-main/**"
  - "androidApp/**"
  - "desktopApp/**"
  - "iosApp/**"
  - "shared/**"
---

# Точки входа (`platform-main`)

- Здесь и только здесь: создание корневого Metro-графа (`createGraph<HeartbeatGraph>()` / factory с платформенными зависимостями), корневого Decompose-компонента, `Log.init(isDebug)`, выбор UI-кита (`LocalPlatformUi`).
- Порядок старта: `Log.init` → граф → root `ComponentContext` (с `LifecycleRegistry`) → `HbTheme { RootContent(root) }`.
- Android: `defaultComponentContext()` в `Activity`; не держи ссылки на `Activity` в графе.
- Desktop: `LifecycleController` + `runOnUiThread` для создания root-компонента; определение ОС → `PlatformUi.Fluent` (Windows) / `PlatformUi.MacOs` (macOS) / `Material` (прочие).
- iOS: `MainViewController()` в `platform-main:shared` (iosMain), lifecycle из `ApplicationLifecycle`; Swift-код в `iosApp/` — минимальный.
- Никакой бизнес-логики и UI фич — только сборка.
- Шаблонные модули `androidApp`/`desktopApp`/`shared` — переносятся в `platform-main` (см. `docs/ai/architecture.md#миграция-из-шаблона`).
