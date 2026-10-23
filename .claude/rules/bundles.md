---
paths:
  - "bundles/**"
  - "core/bundles/**"
---

# Бандлы desktop (`bundles/*`, `core/bundles`)

Desktop делится на бандлы, которые выпускаются и обновляются отдельно (план — поезд бандлов; платформа — JRE, лаунчер,
загрузчик). Бандл — единица DI, тестов, доставки, отката и выключения. Состав: `foundation` (core, дизайн-система,
Compose), `contracts` (все `features:*:api`), `engines`, `web`, `agent-runtime`, `studio`, `shell`.

- **`studio`** собирает студию и спутников (`attachments`, `autocomplete`, `checklist`, `questionnaire`, `planning-mode`,
  `effort-configuration`, `context-window-optimization`, `feedback`, `ai-session-engine-transfer`, `computer-use`,
  `service-as-ui`, `ai-studio`) по шагам; фича, которой ещё нет в `bundles/studio/build.gradle.kts`, остаётся в фундаменте.
- **Сборщик бандла** — модуль `:bundles:<name>` с плагином `heartbeat.bundle` (только JVM). Он зависит от `…:impl`
  своих фич и держит собственный Metro-граф и `BundleEntry` (`core:bundles:api`). Код фич не меняется: те же
  `@Contributes*`.
- **Внутри бандла** Metro статичен. **Между бандлами** — только рантайм-реестр `core:bundles` (`BundleHost`):
  бандл импортирует чужое интерфейсом из аксессоров без квалификаторов (`<Bundle>Imports`, хост подставляет Proxy),
  а отдаёт интерфейсами `<Bundle>Exports`, которые реализует его граф. Это осознанное исключение из правила 5 «без
  сервис-локаторов»: реестр живёт только на границе бандлов и никогда не попадает в код фич (запрещён в `features/*`: `heartbeat:FeatureLayerDependency` ловит ссылки на
  `io.aequicor.heartbeat.core.bundles` из пакетов `feature.*`).
- **Границы (проверяет `heartbeat.detekt` и `verifyBundleMembership`)**: бандл не зависит от другого бандла;
  на `:bundles:*` ссылаются только модули `platform-main`; каждый `…:impl` входит ровно в один бандл (две копии
  контрибуций дали бы два экземпляра); `:platform-main:di-bundle` остаётся графом фундамента и мобильных платформ.
- **Импорты ленивы.** Не вызывай чужой импорт при создании графа — только через `Provider`/`Lazy` или при обращении.
  Реальный цикл между бандлами даёт `BundleCycleException`.
- **Наборы** (`Set<T>`): один владелец собирает набор из своего и чужих вкладов, остальные только экспортируют свою часть;
  бандл не импортирует и не экспортирует один и тот же набор. Map-мультибиндинги бандл не пересекают.
  Набор, которым владеет бандл (например `Set<AgentToolContribution>` у `engines`: его собирает фасад), бандл собирает из
  своих контрибуций, частей других бандлов (их `<Bundle>ProfileExports`) и части фундамента, которую фундамент отдаёт
  держателем `FoundationProfileBindings.engineSets` (`agentTools`, `nativeActionAnalyzers`, `toolOutputOffloads`,
  `sessionHooks`, `toolPolicyProviders`, `contextPolicyProviders`, `virtualWorkspaceProviders`, `engineRegistrations`).
  Владелец у набора один: `engines` собирает набор из своих контрибуций, части фундамента и части `web`
  (`EnginesProfileImports`), а фундамент свой набор больше не собирает из чужих экспортов. Аксессор фундамента отдаёт
  только то, что фундамент компилирует сам. Объявления `@Multibinds` пустых наборов остаются и у владельца, и в
  фундаменте (`EngineOwnedSets`). Наборы отдаются через держатель, а не `Set`-аксессорами интерфейса из `@Includes`:
  такой аксессор сам становится биндингом и подменяет мультибиндинг графа (проверено на `engines`: терялись вклады
  фундамента). Сервис, которым владеет бандл, нельзя оставлять аксессором в `FoundationProfileBindings`, если его
  включает граф самого владельца (два биндинга одного типа): так `ModelProfilesView` вынесен в
  `FoundationProfileModelBindings`, который включает только `engines`. По той же причине импорты бандла передаются в фабрику профильного графа как `@Provides`, а не
  `@Includes`. Полный список регистраций движков фундамент читает через `RegisteredEngines`, а не через
  `Set<EngineRegistration>` (иначе набор читал бы сам себя).
- **Наборы `agent-runtime`.** Владелец набора определяется тем, кто его читает. Планировщик и конфигурация проекта читают
  `ScheduledSessionHost`, `ScheduledWakeOwner`, `ScheduledEventOwner`, `HelperCapacityRecoverySource`,
  `SchedulerEventSource` и `ConfigSection`, поэтому их владеет бандл, а часть фундамента (студия, чек-листы, интерфейсы)
  приходит через `FoundationProfileBindings.agentRuntimeSets`; `AgentRuntimeOwnedSets` в фундаменте держит только
  `@Multibinds`. `ScheduledHelperPromptOwner` и `ScheduledRequestOriginObserver` читает фундамент (студия), поэтому их
  владеет фундамент, а харнессы бандла отдают свою часть экспортом. `ServiceUiProvider` читает service-as-ui, то есть
  `studio`: он владеет набором и берёт часть харнессов у `agent-runtime` (`StudioProfileImports`), а фундамент его больше
  не собирает. Тот же принцип, что у `engines`: набор нельзя одновременно собирать из чужого экспорта и отдавать ему свою
  часть (получится цикл). Если владелец уже экспортирует набор целиком, а часть нужна ему от другого бандла, часть
  едет под отдельным ключом-держателем: `ConfigSectionPart` (`core:bundles:api`) несёт разделы конфигурации
  `service-as-ui` из `studio` в дерево `agent-runtime`, которое тот целиком экспортирует как `Set<ConfigSection>`.
- **Бандл `studio`.** Фичи вокруг студии отдают фундаменту сервисы экспортом профильного графа (`StudioProfileExports`:
  `EffortChoicesView`, `PlanningView`, `ComposerAssists`, `ContextOptimizationView`, экраны опросников и чек-листов,
  машина переноса сессии), а фундамент читает их через `FoundationProfileBundleImports`. Инструменты агента, хуки сессий,
  выгрузки вывода и политики контекста бандл отдаёт наборам, которыми владеет `engines` (`EnginesProfileImports`);
  владельцы наборов планировщика — `agent-runtime`, который берёт владельцев пробуждений чек-листов у `studio`
  (`AgentRuntimeProfileImports`). Шину планировщика `studio` получает от фундамента через
  `FoundationProfileRuntimeBindings` (отдельный интерфейс по той же причине, что и `FoundationProfileModelBindings`).
  Документы и превью браузера для интерфейсов `service-as-ui` бандл берёт у `web` (`StudioProfileImports`, без `web`
  подставляются заглушки). Окно-презентацию захвата и гид выдачи прав `computer-use` отдаёт хосту экспорт уровня
  приложения (`StudioAppExports`, импорт `FoundationBundleImports`). Вложения (`attachments`) бандл отдаёт каталогом и
  резолвером ресурсов; `web` читает их у фундамента через `FoundationProfileAttachmentBindings` (владелец не включает
  этот интерфейс: иначе у него два биндинга одного типа). `ai-studio` пока остаётся в фундаменте: он тянет сервисы
  `agent-runtime`, `engines`, `web` и `shell`, владеет наборами владельцев подсказок планировщика, `StudioRemoteScreens`
  читает Chromium-мост, а интеграционные тесты строят `AiStudioGraph` из профильного графа фундамента.
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
