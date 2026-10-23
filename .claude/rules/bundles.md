---
paths:
  - "bundles/**"
  - "core/bundles/**"
---

# Бандлы desktop (`bundles/*`, `core/bundles`)

Desktop делится на бандлы, которые выпускаются и обновляются отдельно (план — поезд бандлов; платформа — JRE, лаунчер,
загрузчик). Бандл — единица DI, тестов, доставки, отката и выключения. Состав: `foundation` (core, дизайн-система,
Compose), `contracts` (все `features:*:api`), `engines`, `web`, `agent-runtime`, `studio`, `shell`.

- **Сборщик бандла** — модуль `:bundles:<name>` с плагином `heartbeat.bundle` (только JVM). Он зависит от `…:impl`
  своих фич и держит собственный Metro-граф и `BundleEntry` (`core:bundles:api`). Код фич не меняется: те же
  `@Contributes*`.
- **Внутри бандла** Metro статичен. **Между бандлами** — только рантайм-реестр `core:bundles` (`BundleHost`):
  бандл импортирует чужое интерфейсом из аксессоров без квалификаторов (`<Bundle>Imports`, хост подставляет Proxy),
  а отдаёт интерфейсами `<Bundle>Exports`, которые реализует его граф. Это осознанное исключение из правила 5 «без
  сервис-локаторов»: реестр живёт только на границе бандлов и никогда не попадает в код фич (запрещён в `features/*`).
- **Границы (проверяет `heartbeat.detekt` и `verifyBundleMembership`)**: бандл не зависит от другого бандла;
  на `:bundles:*` ссылаются только модули `platform-main`; каждый `…:impl` входит ровно в один бандл (две копии
  контрибуций дали бы два экземпляра); `:platform-main:di-bundle` остаётся графом фундамента и мобильных платформ.
- **Импорты ленивы.** Не вызывай чужой импорт при создании графа — только через `Provider`/`Lazy` или при обращении.
  Реальный цикл между бандлами даёт `BundleCycleException`.
- **Наборы** (`Set<T>`): один владелец собирает набор из своего и чужих вкладов, остальные только экспортируют свою часть;
  бандл не импортирует и не экспортирует один и тот же набор. Map-мультибиндинги бандл не пересекают.
  Набор, которым владеет бандл (например `Set<AgentToolContribution>` у `engines`: его собирает фасад), бандл собирает из
  своих контрибуций, частей других бандлов (`<Bundle>ProfileImports`, профильные импорты) и части фундамента, которую
  фундамент отдаёт `FoundationProfileToolBindings` (`agentTools`, `nativeActionAnalyzers`, `toolOutputOffloads`,
  `sessionHooks`, `toolPolicyProviders`, `contextPolicyProviders`, `virtualWorkspaceProviders`). Владелец берёт его
  `@Provides`-экземпляром фабрики профильного графа и добавляет каждый набор через `@ElementsIntoSet`
  (`EnginesAgentRuntimeSets`); `@Includes` нельзя — аксессор `Set<T>` подменит мультибиндинг графа и потеряет вклады
  владельца. Остальные бандлы (`web`) этот интерфейс не берут. Владелец у набора один: `web` экспортирует свою часть,
  `engines` забирает её вместе с частью фундамента, а фундамент свой набор больше не собирает из чужих экспортов.
  Аксессор фундамента отдаёт только то, что фундамент компилирует сам. Объявления `@Multibinds` пустых наборов остаются
  у владельца; фундамент объявляет их же для себя (`FoundationAgentRuntimeSets`). Полный итог набора владелец отдаёт
  фундаменту (диагностике, тестам) не как `Set<T>`, а как `List<T>`: ключ списка не склеивается с набором фундамента.
- **Бандл `agent-runtime`** (harness, organic-ai, scheduler, adaptive-subagents, worktree-mode, project-config,
  agent-learning) берёт сервисы `engines` прямым профильным импортом (`AgentRuntimeEngineServices` через `@Includes`),
  а части своих наборов от фундамента — `FoundationProfileRuntimeBindings` (`@Provides`, как инструменты у `engines`).
  Свои вклады в наборы `engines` он экспортирует наборами, а в наборы, которыми владеет сам и читает фундамент
  (подсказки помощников, наблюдатели происхождения запросов, секции конфигурации), — списками.
- **Бандл `studio`** (ai-studio, attachments, autocomplete, checklist, questionnaire, planning-mode, effort-configuration,
  context-window-optimization, feedback, ai-session-engine-transfer, computer-use, service-as-ui) берёт сервисы других
  бандлов прямым профильным импортом (`StudioServices` через `@Includes`; множества — через `StudioProfileImports`), отдаёт
  `engines` и `agent-runtime` свои части их наборов, а фундаменту — `StudioProfileExports`/`StudioAppExports`
  (экраны студии, каталог вложений, `ResourceResolver`, computer use). `studioRemoteScreens` и `attachmentsCatalog`
  живут в `FoundationProfileStudioBindings` (их берут `web` и `agent-runtime`).
- **Тесты.** Интеграционные тесты `di-bundle` собирают студию статически (`jvmTest.dependencies(studioImpls)`,
  мосты студии исключены в `TestAppGraph`), потому что они ходят во внутренности студии; `engines`, `agent-runtime` и
  `web` грузятся в рантайме, как на desktop. Настоящий бандл `studio` и все остальные запускает `DesktopBundlesTest`
  (`:platform-main:desktop`): граф приложения с бандлами на class path, здоровье бандлов и их профильных графов.
- **Вклады в движки извне.** Чужие адаптеры движков приходят в `engines` профильным импортом
  `EnginesProfileImports.engineRegistrations` (так в тестах: `TestEngineBundle`); `engines` отдаёт итоговый список
  `registeredEngines`.
- **Граница реестра проверяется `BundleBoundary`** (detekt): `features/*` не импортирует `core:bundles` и бандлы,
  платформенный код не импортирует бандлы и не приводит граф к типу `…Graph` (только к интерфейсу аксессоров).
- **Контрибуции из не-impl библиотек** (например `core:common`) попадают в граф каждого бандла. Публичные снимает
  `excludes` на `@DependencyGraph`; `internal` нельзя назвать в `excludes` — перекрывай их
  `@ContributesBinding(priority = …)` в бандле (проверено spike-тестом) или оставляй дубликат, если класс без состояния.
- **Профильный граф бандла.** Фичи профиля (`ProfileScope`) живут в `@GraphExtension(ProfileScope::class)` бандла
  (`WebProfileGraph`), который создаётся из app-графа бандла (`val profileGraphs: …Graph.Factory`) и получает
  `@Includes FoundationProfileBindings`. `BundleEntry.hasProfileGraph = true`, `createProfileGraph` и
  `profileExportTypes` отдают наружу `<Bundle>ProfileExports` и `ProfileStartupAccess`. App-граф бандла исключает
  `ProfileGraph.Factory` (`excludes`), чтобы не собирать профильный граф фундамента. Фундамент забирает профильные
  части через `FoundationProfileBundleImports` (`ProfileBundleImports.kt`): отсутствующий бандл даёт запасной вариант
  (`orElse`), закрытие профиля роняет профильный граф бандла вместе со скоупом.
- **Расширение `Foundation*Bindings`.** `FoundationAppBindings` и `FoundationProfileBindings` (`core:bundles:api`) —
  единственный путь, которым бандл берёт состояние фундамента; объявляй в них только то, что бандлу нужно сейчас,
  типом из `api`-модуля (контракты), не из `impl`. Новый аксессор добавляется в интерфейс и в `DefaultFoundation*Bindings`
  (`core:bundles:impl`) через `Provider` — чтение ленивое. Область аксессора совпадает с областью сервиса: app — в
  `FoundationAppBindings`, profile — в `FoundationProfileBindings`. `api` фичи, которой владеет бандл, в
  `core:bundles:api` не попадает: тип переезжает вместе с владельцем (так `ExternalBrowser` покинул фундамент после
  переноса `chromium-bridge` в `web`). Когда владелец сервиса сам станет бандлом, аксессор заменяется импортом
  `<Bundle>Imports`. Android и iOS эти интерфейсы не видят: `…:impl` бандла там остаются в статическом графе
  (`androidMain`/`iosMain` в `platform-main/di-bundle`).
- **Сбой бандла изолирован**: исключение при создании графа даёт `BundleHealth.Failed`, нехватка экспортёра —
  `Degraded`. Состояние читает лаунчер, чтобы откатить поезд.
