---
paths:
  - "platform-main/**"
---

# Точки входа (`platform-main`)

- Metro-граф — только в `platform-main:di-bundle`: `HeartbeatGraph` в `commonMain` без аннотации + `@DependencyGraph(AppScope::class)` в каждом платформенном source set'е (`AndroidHeartbeatGraph`, `JvmHeartbeatGraph`, `IosHeartbeatGraph`) — иначе Metro не видит платформенные контрибуции. Точки входа вызывают `createHeartbeatGraph(...)`.
- `di-bundle` — единственный модуль, который зависит от `…:impl` (фич и `core:*:impl`). Точки входа `android`/`desktop`/`shared` зависят от `di-bundle`, не от `impl`.
- Точки входа: создать `HeartbeatRoot(context, graph, RootStart(...))` (di-bundle, один раз на Activity/окно/UIViewController), передавать ему deep links (`handleDeepLink`), `Log.init(isDebug)`, выбор UI-кита (`LocalPlatformUi`), рендер — `RootContent(root)` из `platform-main:root`. Восстановление профиля, переключение гость/профиль и отложенные deep links root делает сам (скилл `navigation`, раздел 9).
- Доступ root к профильным entry point'ам — интерфейс-аксессор `@ContributesTo(ProfileScope::class)` и `session.graph as <Accessors>`.
- Порядок старта: `Log.init` → `createHeartbeatGraph()` → root `ComponentContext` (с `LifecycleRegistry`) → `HbTheme { RootContent(root) }`.
- Android: `defaultComponentContext()` в `Activity`; не держи ссылки на `Activity` в графе.
- Desktop: `LifecycleController` + `runOnUiThread` для создания root-компонента; определение ОС → `PlatformUi.Fluent` (Windows) / `PlatformUi.MacOs` (macOS) / `Material` (прочие).
- iOS: `IosHeartbeatHost` в `platform-main:shared` (iosMain) держит root на `ApplicationLifecycle` и отдаёт `viewController()`; его хранит `AppDelegate`. Swift-код в `platform-main/ios/` — минимальный.
- Никакой бизнес-логики и UI фич — только сборка. Исключение — хром сессии агента computer-use: root наблюдает
  `ComputerUseMachineKey` и один раз выводит студию вперёд при захвате агентом, `App` рисует плашку сессии с кнопкой
  «Стоп», desktop закрепляет окно у края, скрывает его на время снимка и ввода мышью, а при захвате рабочего стола
  рисует тень по периметру экранов. Решения остаются у фичи: что
  показывать — `computerUseActivity()` из `computer-use:api`, остановка — интент `StopAgent` машины; хост только
  отображает и пересылает. Так же устроен гид выдачи прав macOS: пока `ComputerUsePermissionGuide.guide` из графа
  называет разрешение, desktop показывает у окна System Settings неактивирующую панель (`ComputerUsePermissionGuidePanel`
  из `shared`) с плиткой приложения, которому macOS выдаёт право (ответственный процесс: в разработке — IDE или
  терминал), и перетаскиванием этой плитки в список; закрытие — `dismiss()`. Когда показывать и скрывать гид,
  решает фича.
  Так же подключаются диаграммы markdown: desktop берёт `PlantUmlRenderer` через `PlantUmlAccessors` (jvmMain
  `di-bundle`), адаптирует его к SPI ДС (`PlantUmlDiagramRenderer`: стиль строки → запрос, PNG декодируется на
  `dispatchers.default`) и передаёт в `App(root, diagramRenderer = …)`, пока `availability` фичи true; `App` оборачивает
  дерево в `HbDiagramsProvider` с подписями из ресурсов `shared`. Что и как рисовать, решает фича; мобильные хосты
  рендерера не передают — fence остаётся кодом. Рисует процесс-воркер фичи: в упакованном jlink-рантайме нет `java`,
  поэтому `Main`/`DevelopmentMain` до любой инициализации передают аргумент `--heartbeat-plantuml-worker` в
  `runPackagedPlantUmlWorker` (как `runPackagedBuildWorker`), а `compose-desktop.pro` сохраняет его `main`.
- Модули: `platform-main:shared` (общий вход `createAppRoot` + `App`, статический iOS framework `Shared`), `platform-main:android`, `platform-main:desktop`, Xcode-проект `platform-main/ios` (build phase — `:platform-main:shared:embedAndSignAppleFrameworkForXcode`). Пакеты — `io.aequicor.heartbeat.platform.<модуль>`; applicationId `io.aequicor` не меняй без согласования (id в сторах).
