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
  на `:bundles:*` ссылаются только модули `platform-main`; каждый `…:impl` на desktop имеет ровно одного владельца — один бандл или статический граф фундамента
  (`commonMain`/`jvmMain` `:platform-main:di-bundle`): две копии контрибуций дали бы два экземпляра, а `…:impl` без
  владельца молча выпал бы из приложения; `:platform-main:di-bundle` остаётся графом фундамента и мобильных платформ.
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
  читает Chromium-мост, а интеграционные тесты строят `AiStudioGraph` из профильного графа фундамента. Вместе с ней в
  фундаменте остаётся `auto-continue`: студия реализует её SPI `AutoContinueHost` и сообщает ей о ходах.
- **Контрибуции из не-impl библиотек** (например `core:common`) попадают в граф каждого бандла. Публичные снимает
  `excludes` на `@DependencyGraph` — в том числе `@ContributesBinding` публичного класса из другого модуля (проверено
  spike-тестом с фикстурой `core:bundles:spike-fixture`: исключённая библиотечная привязка заменяется привязкой бандла,
  без `excludes` они конфликтуют); `internal` нельзя назвать в `excludes` — перекрывай их
  `@ContributesBinding(priority = …)` в бандле (проверено spike-тестом) или оставляй дубликат, если класс без состояния.
- **Дубликаты состояния фундамента.** Ключи `FoundationAppBindings` и `FoundationProfileBindings` (кроме наборов) принадлежат
  фундаменту: `BundleHost` при регистрации экспортов помечает бандл `Failed`, если он экспортирует такой ключ (второй
  экземпляр stateful-синглтона рядом с тем, что фундамент отдаёт всем графам). Исключение — осознанно меньший набор
  ключей в конструкторе хоста, не молчаливый экспорт. Статические контрибуции из библиотек этой проверкой не видны —
  для них действуют `excludes` выше и `verifyBundleMembership`.
- **Артефакты поезда.** `foundation` и `contracts` описываются в `bundleManifest` как базовые артефакты (`"kind": "base"`;
  у `contracts` зависимость `foundation`; у обычных бандлов поле `kind` не пишется). Готовые jar-ы подаются
  `-Pheartbeat.baseArtifacts=foundation,contracts -Pheartbeat.baseArtifactDir=<каталог с bundle-<id>-<версия>.jar>`;
  версия — `heartbeat.bundleVersion`. Jar-ы собирает плагин `heartbeat.train` (`:platform-main:desktop`): `assembleFoundation` (код проекта, не вошедший в контракты и бандлы) и `assembleContracts` (`:features:*:api`) попадают в `bundleManifest`; сторонние библиотеки остаются в платформе установщика, а не в поезде.
- **SDK поезда и обработка ProGuard.** Явный список модулей фундамента — `heartbeatTrain { sdk(...) }` в
  `platform-main/desktop/build.gradle.kts`: `contracts` целиком, `core:bundles:api` и публичное API перечисленных модулей.
  Бандл и его `…:impl` видят из фундамента только SDK (прямо и через `api`-зависимости контрактов и SDK-модулей); корневая
  `verifyTrainSdk` (в `check` desktop и каждого бандла) падает при выходе за SDK. Новый модуль фундамента, нужный бандлу,
  вносится в список явно, вместе с его транзитивными `api`-зависимостями.
  `-Pheartbeat.trainShrink=off|shrink|obfuscate` (по умолчанию `off`, разработка и тесты идут на необработанном classpath):
  `foundation` и каждый бандл проходят отдельный прогон ProGuard (`shrinkFoundation`, `shrinkBundle`); `contracts` и
  сторонние библиотеки платформы не обрабатываются. Порядок: `foundation` (вместе с `contracts`, которые сохраняются
  целиком) → бандлы против обработанного `foundation`, `contracts` и jar установленной платформы (`TrainPlatformJars`).
  `assembleFoundation` и `assembleBundle` остаются задачами, дающими итоговый jar в `build/bundle`; сырой лежит в
  `build/bundle-raw`. SHA-256 манифеста, seed и дельты считаются по обработанным jar; выход нормализуется (время записей
  jar), одинаковые входы дают одинаковые байты.
  Keep-правила: `generateTrainSdkRules` строит `-keep` для публичного API SDK по Kotlin-метаданным (`public`/`protected`/
  `@PublishedApi`; `internal`/`private` свободны; Java — по флагам байткода); `platform-main/desktop/train-rules.pro` —
  то, что не выводится из SDK (входы лаунчера и воркеров, `BundleEntry`, `Foundation*`-интерфейсы, сериализация, enum, Room,
  API харнесса для скриптов). Правила пишутся только про код Heartbeat; `-dontwarn` не добавляй — предупреждение ProGuard
  означает ссылку бандла за пределы SDK или платформы. Код, который грузится по имени (`Class.forName`, `ServiceLoader`,
  рефлексивные аксессоры, имена сериализаторов, JNA-интерфейсы/структуры/колбэки с нативными именами), требует строки в
  `train-rules.pro`: тест `BundleKeepRulesTest` следит за интерфейсами `core:bundles:api` (`Foundation*` и `*Part`), правилами
  JNA и формой условий сериализации. Что в `-if` стоит до первого `**`, не попадает в `<1>`: условие пиши `class **`.
  Ограничения: граница SDK не видит тела публичных `inline`-функций (их ссылки на не-SDK модули ловит только смоук); простые
  имена классов, которые код пишет в лог через `::class.simpleName`, `retrace.py` не восстанавливает; sealed-иерархии с вложенным
  sealed-интерфейсом проходят проверку только в рантайме (следи за `IncompatibleClassChangeError`).
  `obfuscate` дополнительно сокращает имена внутренностей: пакеты остаются, имена классов — из словаря артефакта (префикс из
  SHA-256 его id), оптимизации нет. Маппинги — `build/train/mapping-bundle-<id>-<версия>.txt`; репозиторий публичный, обфускация
  нужна ради размера и скорости, не секретности. Запуск пишет в лог `Launch: train=<id> version=<v>`;
  `python3 scripts/retrace.py --mappings <каталог> [--manifest train-manifest.json] <лог или ZIP>` восстанавливает имена
  классов и методов в стек-трейсах.
  Проверка: `./gradlew -Pheartbeat.trainShrink=shrink|obfuscate :platform-main:desktop:verifyShrunkTrain` — headless-запуск
  production-графа на обработанных jar поезда (список — из `bundleManifest`), открытие профиля, все бандлы обязаны быть
  `Healthy` и на уровне приложения, и в профильном графе (`BundleHost.lastProfileHealth`); логи прогона — `build/train-smoke/smoke.log`
  (смоук `TrainSmoke` в тестах desktop; не покрывает UI и JCEF-браузер). Перед включением `obfuscate` в CI прогнан `e2e-regression` (core) на упакованном
  `HeartbeatE2E`; в CI смоук идёт перед записью поезда, маппинги публикуются вместе с jar (`ci.md`).
- **Лаунчер в поезде.** `assembleLauncher` собирает jar лаунчера (`TrainPart.Launcher`), `bundleManifest` пишет его записью
  `kind=launcher` с `launcherApi` и подписью Ed25519, `scripts/train_manifest.py` публикует эти поля. Лаунчер не входит в
  classpath приложения; слоты, селектор и подпись — `.claude/rules/launcher.md`.
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
  `Degraded`. Экспортёр, упавший при первом чтении импорта, тоже становится `Failed` (в профиле — только для этого
  профиля): его экспорты снимаются, потребитель получает `BundleUnavailableException` с причиной, а сбор набора
  пропускает его часть. Уровень снятия — тот, на котором экспортёр зарегистрирован (профильный экспорт снимается только
  в своём профиле, приложение остаётся здоровым). Снимается только сам упавший экспортёр: бандл, чей аксессор лишь
  передал недоступный импорт, цикл или состояние хоста (бандлы ещё создаются, профиль открывается или закрыт —
  `BundleUnavailableException`), не виноват и остаётся. Снятие закрывает только будущие разрешения: уже выданные
  экземпляры у держателей не отзываются. Поэтому импорт, без которого окно не работает, держит запасной вариант
  (`orElse`/заглушку) и в фундаменте — синглтон `@SingleIn(AppScope::class)`: окно читает его при каждой рекомпозиции,
  и без скоупа каждое чтение заново шло бы в хост (и логировало запасной вариант) и могло бы подменить живой экземпляр
  заглушкой. Состояние читает лаунчер, чтобы откатить поезд.
