# Aequicor Heartbeat

ИИ-студия на Kotlin Multiplatform + Compose Multiplatform.
Платформы: **Android, iOS, Desktop macOS, Desktop Windows** (desktop = один JVM-таргет, выбор UI-кита — в рантайме по ОС).
Корневой пакет: `io.aequicor.heartbeat`. Язык общения в коде/доках: русский в документации, английский в идентификаторах и KDoc.

## Где правила

Источник правды по коду — KDoc в исходниках. Правила для агентов — этот файл и `AGENTS.md`.
В Claude Code правила по путям подгружаются автоматически из `.claude/rules/`; Codex читает применимые правила явно согласно `AGENTS.md`.
Общие процедуры (создать фичу, машину, стор, тогл…) — скиллы в `.claude/skills/`.
Codex обнаруживает одноимённые навыки в `.agents/skills/`; они направляют к этим общим процедурам.

## Группы модулей

```
platform-main/   точки входа: android, desktop, ios (+ shared umbrella/framework);
                 di-bundle — единственный модуль, видящий все impl: Metro-граф (per-platform)
core/            инфраструктура: navigation, mvi, state-machine, di (api/ext/impl), profile-facade (api/impl),
                 resources, datastore, network, ai, feature-toggles, logging, common, desktop-dialogs
design-system/   tokens (единый набор, пресеты по хосту), theme, components, layouts, resources, catalog, adaptive (material | fluent | macos)
features/<name>/ api  — контракт: state-machine (состояния, интенты, переходы, эффекты), MachineKey, маршруты
                 impl — UI, FlowMVI-сторы, Decompose-компоненты, репозитории, эффекты машины, DI-контрибуции
build-logic/     convention-плагины Gradle (heartbeat.kmp.library, heartbeat.feature.api/impl, heartbeat.detekt…)
lint/            detekt-rules — собственный набор правил `heartbeat` (политика логирования и обработки ошибок)
```

> Текущее состояние: шаблон перенесён в `platform-main` — `shared` (общий вход, iOS framework `Shared`), `android`, `desktop`,
> Xcode-проект `ios`; пакеты `io.aequicor.heartbeat.platform.*`, applicationId `io.aequicor` сохранён.
> AI-движки: контракты `features:ai-engine:{facade,authenticator}:api`; `authenticator:impl` — источники авторизации профиля и проверки;
> `facade:impl` — каталог движков и привязок, выбор движка по умолчанию, модели, Room-индекс сессий, пул runtime, `ActiveSession` на машине.
> Адаптер `koog:{api,impl}` (OpenAI, Anthropic, OpenAI-/Anthropic-совместимые серверы по HTTPS или loopback, локальный Ollama, потоковые текстовые сессии, история и подключения профиля)
> подключён в DI, включение — тогл `ai.koog` (по умолчанию false).
> Кодинг-сессии Koog (только Desktop, тогл `ai.koog.coding_tools`): сессия на проекте получает файловые инструменты и `run_command`
> в корне проекта; запись и команды — автоматически (тогл `ai.koog.auto_approve`, по умолчанию true) или через подтверждение `session.permissions`.
> Локальный Desktop-адаптер `codex:{api,impl}` — app-server, сессии, текстовый стриминг, отмена и approvals (тогл `ai.codex`).
> `claude:{api,impl}` — desktop Claude Code CLI, профильный runtime текстовых сессий, CLI-авторизация, обнаружение моделей,
> частичная история наблюдённых ходов (тогл `ai.claude`, по умолчанию false); внешняя история CLI и tools/permissions пока нет.
> Подключение движков — `features:ai-engine:connections` (визард «движок → авторизация → модели» и пространство
> «движок × подключение × модель», тогл `ai.engine_connections`) поверх `EngineFacade` и `AuthSources`; экраны показывают
> движки, объявившие `connectionMethods` (Pi, Koog, Codex, Claude); адаптеры узнают источники привязок через SPI `bind`/`unbind`.
> Управление движками (тогл `ai.engine_management`, по умолчанию false) — блок выбранного движка в «Модели и движки» поверх
> `EngineManagement` (`facade:api`): вкл/выкл в профиле поверх флагов разработки, статус и пути, своя проверенная (SHA-256) копия
> CLI Heartbeat (Codex, Claude; Pi — обновление с откатом на встроенную), вход/выход CLI, параметры запуска, перезапуск рантаймов;
> адаптеры реализуют SPI `EngineManager`, длительные задачи принадлежат профилю.
> `ai-studio` использует профильный фасад, сохраняет идентичность и историю чатов; принятые ходы принадлежат профилю
> и переживают закрытие экрана (тогл `ai_studio.engine_runtime`, по умолчанию false — демо-пространство и старт профиля с welcome).
> ACP v1: `features:ai-engine:acp-interface:{api,impl}` — общий клиент JSON-RPC, сессии, updates, permissions;
> stdio на Desktop, явный отказ запуска desktop-процессов на мобильных платформах. Конкретные движки подключаются отдельно.
> `features:ai-session-engine-transfer:{api,impl}` — перенос сессии на другой движок (handoff-транскрипт, цепочка сегментов
> логической беседы в profile KV); машина в ProfileScope создаётся лениво, `EngineFacade` — опциональная зависимость, UI нет.
> Готово: `build-logic` (`heartbeat.detekt`, `heartbeat.kmp.library`, `heartbeat.metro`, `heartbeat.room`), `core:logging`, `core:common`,
> `core:di:{api,ext,impl}`, `core:profile-facade:{api,impl}`, `platform-main:di-bundle` (скоупы app → profile → feature → screen),
> `core:navigation:{api,impl,compose}`, `core:state-machine:{api,impl,flowmvi-ext}`,
> `core:network:{api,impl}`,
> `core:datastore:{api,impl}` (key-value + БД фич, владельцы app/profile, удержание записей),
> `core:secrets:{api,impl}` (защищённые секреты профиля и ссылки), `core:feature-toggles:{api,impl}` (тоглы, реестр, локальные переопределения, `FeatureToggleControl`).
> `features:ai-engine:{facade:{api,impl},pi:{api,impl}}` — встроенный движок Pi по умолчанию только на Desktop (Windows/macOS)
> (бинарь Pi вшит в дистрибутив задачей `preparePiRuntime`, данные — в каталоге приложения `<app data>/engines/pi`; установщик Windows на Inno Setup (`packageInnoSetup`) при удалении чистит данные пользователя — `platform-main/desktop/packaging/windows/heartbeat.iss`)
> за тоглами `ai.engines` + `ai.pi` (вендорные ключи и OpenAI-/Anthropic-совместимые серверы — `CompatibleProtocol` в `facade:api`), изменяющие вызовы инструментов — по уровню доверия хода (`TrustLevel` в `facade:api`: спрашивать / авто-правки / полное доверие, выбор в композере ai-studio), иначе после подтверждения пользователя;
> на Android/iOS — заглушка «не поддерживается».
> Дизайн-система: `design-system:{tokens,adaptive,theme,resources,layouts,components,catalog}`;
> отдельная `platform-main:uikit-sandbox:{desktop,android,shared}` и iOS Xcode app — [запуск](platform-main/uikit-sandbox/README.md).
> `features:effort-configuration:{api,impl}` — машина выбора reasoning effort по маршруту модели (ProfileScope); ai-studio шлёт выбор
> через неё, движки Codex/Claude/Pi/Koog объявляют и применяют уровни; сохранение стартового выбора в профиле — тогл `ai.effort_configuration` (по умолчанию true); изменения effort в существующем чате остаются локальными для чата.
> Koog узнаёт поддержку effort из API (Anthropic, Ollama), иначе из каталога models.dev (тогл `ai.koog.reasoning_catalog`;
> совместимый маршрут — по разделу вендора, чей origin повторяет), иначе по семейству модели;
> отвергнутые поставщиком параметры — повтор хода без них. Обрезанные стримом tool-calls склеиваются по индексу,
> непригодные вызовы отбрасываются до инструментов и эха в промпт.
> `features:computer-use:{api,impl}` — управление компьютером для тестирования и отладки приложений (только Desktop Windows/macOS):
> два режима захвата (весь рабочий стол и отдельное окно), master-кадр с кропами и тайлами в нативном разрешении, сжатие кадра под
> токен-бюджет агента (пресеты, JPEG/PNG, gray/indexed с дизерингом, лестница снижений под лимит байтов), ввод мышью и клавиатурой
> только внутри захваченной области, hosted-инструменты `computer_*` и контракты `EngineFeature` (`computer.host` у хоста,
> `computer.control` у движка с роутером native → host). Доступ агента — один переключатель профиля (по умолчанию выключен,
> маршрут `computer-use` в настройках): агент сам выбирает режим и цель, ввод вооружается одобренным вызовом через гейт
> доверия, захват принадлежит ходу агента и освобождается в конце хода; выключение отзывает захват (`Revoke`) и без
> открытого экрана. Во время захвата Desktop закрепляет окно сессии у правого края поверх окон с кнопкой «Стоп»
> (интент `StopAgent`), а при захвате рабочего стола — тенью по периметру экранов. В оконном режиме отдельный маркер
> показывает движения агента. Снимок скрывает окно и индикаторы; ввод мышью скрывает только окно сессии.
> Недостающие права macOS (запись экрана, универсальный доступ) выдаются из настроек: кнопка открывает
> страницу System Settings и плавающую панель с плиткой приложения для перетаскивания в список; фича опрашивает права
> и после выдачи перепроверяет машину; тоглы `computer_use.enabled`
> и `computer_use.native_routing` (по умолчанию false).
> `features:agent-learning:{api,impl}` — самообучение агента (тогл `agent_learning.enabled`, по умолчанию false): профильный реестр
> выученных инструкций — общие, для движка или модели, скиллы (в промпте только индекс, тело — инструмент `load_learned_skill`);
> инструкция привязана к проекту, где выучена (worktree — к исходному проекту), или к чатам без проекта и подключается только там.
> Агент сохраняет урок инструментом хоста `remember` (виден в ленте как карточка «Новая инструкция»); подтверждение — уровень
> в разделе настроек «Самообучение»: всегда спрашивать (по умолчанию) / автоматически по оценке безопасности агентом /
> принимать любые; текст в запросе показывается целиком, невидимые символы и похожее на секреты отклоняются.
> Инструкции сессии — `AgentToolContribution.instructions(AgentToolScope)` с бюджетом; инструменты хоста без проекта получают
> только сессии, создатель которых включил `areDetachedToolsEnabled` (ai-studio). `/remember Text` в композере добавляет директиву
> хоста (`hostDirective`, вырезается из ленты и переноса сессии); частые ошибки в выводе инструментов (кодировка, чужой shell, CRLF)
> дают подсказку агенту в следующем ходе.
> `features:organic-ai:{api,impl}` — органический ИИ (тогл `organic_ai.enabled`, по умолчанию false): профильная машина
> организмов сессий. Зигота растёт из цели (`Conceive`), клетки делятся сами инструментом хоста `organism_divide`; результаты детей,
> вердикты и ответы хранятся в очереди и читаются агентом через `organism_receive` (повторное чтение — `after`).
> `wait=true` явно ставит клетку на ожидание: после завершения текущего хода результат будит её служебным напоминанием
> прочитать очередь, без текста результата в чате; ожидание и курсор переживают рестарт. Иммунитет судит
> жалобы (`organism_complain`) и споры (`organism_dispute`) каждый раз в новой сессии без инструментов; убийство лизирует клетку
> с потомками, зигота неподсудна — организм убивает только `Abort`. Организмы живут в профильном журнале и полностью
> восстанавливаются после рестарта (recovery-ход клетки); запросы разрешений клеток эскалируются через состояние машины и `Decide`.
> Клетка ждёт результатов живых детей, решений по своим делам и спорам, в которых участвует; вложения цели передаются первому ходу каждой клетки (включая recovery),
> история клеток и судей сохраняется в профильной БД студии, включая субсессии, которые не открывали на экране. В ai-studio — режим «Органический ИИ» в меню «+» нового чата: первое
> сообщение зачинает организм, завершение отмечено индикатором в заголовке, новое сообщение продолжает зиготу с сохранением истории (если организм не виден — принимает цель заново); справа вверху — субсессии (зигота, клетки, судьи) с состояниями,
> «Остановить» / «Продолжить», запросы разрешений клеток — в панели чата.
> `SessionTrees` (`ai-engine:facade:api`) даёт дерево нативных потомков и их историю без запуска или перенастройки сессий.
> Codex читает дерево через app-server; Claude сохраняет наблюдённые subagent-события и историю. Студия показывает
> вложенность, активность и транскрипты только для чтения; неполное покрытие не выдаётся за подтверждённый ноль.
> Создание нативных субагентов — отдельный тогл `ai.subagents` (по умолчанию false); просмотр истории доступен и без него.
> `features:plant-uml-support:{api,impl}` — диаграммы PlantUML в markdown чатов и кодинг-сессий (тогл `plantuml.enabled`,
> по умолчанию false): закрытый fence `plantuml`/`puml` ДС делает строкой `Diagram` и рисует через SPI `HbDiagramRenderer`;
> только Desktop — встроенный `plantuml-mit` в отдельном процессе-воркере (свой малый heap, убивается по таймауту; SANDBOX,
> Smetana, без сети), тема из токенов, ошибка — строка и исходник, клик — полный размер; на Android/iOS fence остаётся кодом.
> `features:scheduler:{api,impl}` — сон и пробуждение сессий (тогл `scheduler.enabled`, по умолчанию false): агент
> инструментами хоста `scheduler_sleep`/`signal`/`cancel`/`list` засыпает до события, времени или таймера и
> просыпается новым ходом со своей заметкой. Единая профильная шина `SchedulerBus` и ключи `EventKey`
> (`system.network.*`, `session.<id>.turn_finished`, `action.<id>.finished`, `custom.*`); машина `SchedulerMachineKey`
> в ProfileScope хранит пробуждения в профиле (переживают рестарт, доставка at-least-once). Доставка — SPI
> `ScheduledSessionHost`: ai-studio — ходом в чате; без хоста, обслуживающего инструменты и разрешения, сессия не будится.
> Фоновые действия (тогл `scheduler.actions`): `scheduler_start_action` — команда в проекте (Desktop) или агент-помощник
> в новом чате с пробуждением по результату; команды следуют уровню доверия сессии, запуск помощника подтверждает пользователь. Журнал хранит результат
> до завершения связанных пробуждений и повторяет доставку после рестарта или включения планировщика.
> Графы задач (`scheduler.task_graphs`, по умолчанию false, требует enabled/actions): инструменты
> `scheduler_create_graph`/`list_graphs`/`get_graph`/`cancel_graph`/`resolve_interrupted_task`, без отдельного экрана.
> `TaskGraphMachineKey` хранит неизменяемый подтверждённый граф, очередь, попытки и типизированные результаты;
> AllOf/AnyOf ждут успеха или завершения предшественников. Все команды и задания графа подтверждаются вместе;
> автоматические старты и решения координатора используют это разрешение только для владельца и точной попытки.
> Общие лимиты графов и одиночных действий — 3 на владельца, 8 на профиль. Выключение тогла останавливает
> новые старты, сохраняя результаты текущих; просмотр и отмена сохранённых графов доступны.
> Команды запускаются после сохранения идентичности группы процессов/Windows Job; прерванные команды
> требуют решения родительского агента после проверки фактических эффектов и остановки прежнего процесса.
> Агентские задания сохраняют чат и квитанцию принятого хода. `RestoresSessionTurns` в Pi/Codex делает свежую
> проверку движка: совпавший ход наблюдается, подтверждённо свободный чат получает recovery-ход с историей,
> неопределённый исход остаётся ожидающим восстановления. Текст ответа не определяет успех.
> `features:checklist:{api,impl}` — встроенные карточки с чекбоксами, одним выбором и текстом
> (`checklist.enabled`, по умолчанию false). `checklist_create`/`checklist_get` создают и читают карточки;
> ответы вводит пользователь. Атомарный профильный журнал сохраняет черновики и outbox; события и ACK идут
> через `SchedulerBus`. `ResumeSession` использует scheduler; `MarkSessionReady` меняет готовность без нового хода.
> Студия хранит проекцию событий и идентификатор текущего запроса; новый запрос отменяет старые проверки готовности.
> Приложение: `core:mvi`, фичи `welcome`, `ai-studio`, `toggles-panel`, `ai-engine:connections` (профильные маршруты); платформенные входы подключены к root.

## Жёсткие правила (нарушение = блокер ревью)

1. **Зависимости**: `feature:impl` → только `api` других фич. От любого `…:impl` (фич и `core`) зависит только `:platform-main:di-bundle` (проверяет `build-logic` через `heartbeat.detekt`, подключённый ко всем модулям). `core` не знает о `features` и `design-system`. `feature:api` без Compose/UI.
2. **State-machine фичи живёт в `api`** (`machineSpec { }` из `core:state-machine:api`, движок KStateMachine скрыт в `impl`): все состояния, интенты, переходы, эффекты, outputs. Машина запускается в скоупе фичи; другие фичи общаются с ней только через `MachineKey` + `MachineRegistry` → `send(key, Public intent)`. Никаких прямых ссылок на классы `impl`. Исключения для сервисных контрактов, SPI и координации с хостом перечислены в `.claude/rules/feature-api.md`.
3. **UI-состояние** — FlowMVI-стор в `impl`. Машина = бизнес-флоу фичи, стор = состояние экрана. Стор не дублирует состояние машины, а отражает его (`reflect` из `core:state-machine:flowmvi-ext`).
4. **Навигация** — только Decompose через `core:navigation`: фичи открывают друг друга `Navigator.navigate(Route)`, маршруты — `@Serializable @SerialName` в `api`, `RouteEntry` в реестре своего скоупа (`binding<ProfileRouteBinding>()` / `AppRouteBinding`), результаты — `ResultContract`. Никаких navigation-compose и ссылок на чужие компоненты.
5. **DI** — только Metro (`@Inject`, `@ContributesBinding`, `@ContributesIntoMap/Set`, `@GraphExtension`). Граф — только в `platform-main:di-bundle`; скоупы app → profile → feature → screen, граф фичи — через `retainedGraph` (`core:di:ext`). Никаких сервис-локаторов и `object`-синглтонов с состоянием.
6. **Логирование через `core:logging` (Napier), без флуда**: действия пользователя, значимые переходы состояния, запросы и изменения конфигурации — `INFO`; одноразовые диагностические решения — `DEBUG`. Частые внутренние события (токены/дельты, ревизии, polling, проекции состояния, обычный IO БД/DataStore) — только `VERBOSE`, включаемый явно через trace. Логируй границы операции и итог, не каждый элемент на `DEBUG`/`INFO`; не дублируй централизованные логи. Частые обработчики и выделенные из них helpers помечай `@HighFrequency` из `core:logging`: detekt `HighFrequencyLog` запрещает внутри `DEBUG`/`INFO` (также в повторяемых `update`-лямбдах). Ошибки сохраняют `WARN`/`ERROR` с throwable; понижение уровня ошибки для обхода правила запрещено. `println`, `android.util.Log`, `NSLog` запрещены. Секреты и API-ключи не логируются никогда. Проверяется detekt (набор `heartbeat`, `lint/detekt-rules`).
7. **Цвета/типографика/отступы — только токены `design-system`**. `Color(0x…)`, `.sp`/`.dp`-литералы для стилей вне `design-system` запрещены.
8. **Тоглы** — через `core:feature-toggles` (`FeatureToggles` для чтения, регистрация `@IntoSet`); новая функциональность за тоглом по умолчанию.
9. **Корутины**: без `GlobalScope`, `runBlocking` в продовом коде; диспетчеры инжектятся (`DispatcherProvider`); `CancellationException` не глотаем.
   **Ошибки**: никакая ошибка не игнорируется — минимум `log.w(e)`/`log.e(e)` с throwable или проброс (rethrow / `Result.failure(e)`); исключение — `CancellationException`, она пробрасывается. `@Suppress` этих правил запрещён (`ForbiddenSuppress`).
10. **Detekt + compose-rules + ktlint** обязаны быть зелёными. `@Suppress` — только с комментарием-причиной.
11. Версии библиотек приложения — только в `gradle/libs.versions.toml`. Python-инструмент проверки коммитов закреплён отдельно (`scripts/requirements-commit-policy.txt`).
12. **PR содержит законченную фичу; коммиты — отдельные логические шаги.** Каждый коммит должен оставлять проект собираемым, тесты изменённого поведения идут вместе с реализацией. Бюджет каждого коммита — **не более 25 000 токенов полного diff**; свыше 20 000 токенов или 20 файлов — предупреждение. Ориентир — 700–1000 добавленных и удалённых строк суммарно, без жёсткого лимита строк и размера PR. Команды — скилл `verify`.

## Команды

Windows: `.\gradlew.bat`, macOS: `./gradlew`.

```
./gradlew :platform-main:desktop:run           # desktop
./gradlew :platform-main:android:assembleDebug # android
./gradlew allTests                             # все KMP-тесты
./gradlew jvmTest                              # быстрые тесты всех KMP-модулей (JVM)
./gradlew :platform-main:di-bundle:jvmTest     # сборка всего Metro-графа + интеграционные тесты скоупов
./gradlew detekt                               # lint всех модулей (без type resolution, быстро)
./gradlew detekt --auto-correct                # автоформат ktlint-правил
./gradlew :<module>:detektMainJvm              # detekt с type resolution (KMP jvm; :platform-main:desktop:detektMain для JVM-модулей)
./gradlew :lint:detekt-rules:test              # тесты собственных правил detekt
./gradlew --stop                               # после правки lint/detekt-rules: detekt кэширует classloader правил в демоне
./gradlew :<module>:dependencies --configuration commonMainApi   # проверить граф
```

> **`./gradlew --stop` завершает само приложение.** Desktop, запущенный из IDE, — потомок демона Gradle, поэтому
> остановка демонов убивает всё дерево процессов вместе с Heartbeat: мгновенно, без стектрейса и без отчёта о краше,
> с потерей текущего хода агента. Агент, работающий внутри Heartbeat, такую команду не выполняет — движок Pi не
> доверяет её никакому уровню доверия и спрашивает пользователя (`PiHostCommands`). Останавливай демоны только
> во внешнем терминале и только не из-под агента.

iOS собирается только на macOS (Xcode, `platform-main/ios/`). На Windows iOS-таргеты не компилируются — проверяй `commonMain` через `jvmTest`.

## Как работать

- Перед изменением фичи прочитай её `api` (машину) — это спецификация поведения.
- Планируй логические коммиты до реализации; перед каждым коммитом проверяй staged diff через `python scripts/check_commit_size.py --staged`, перед ревью — весь диапазон PR через `--base <base-ref> --head HEAD`. Промежуточные fixup-коммиты объединяй с соответствующим логическим шагом перед ревью; правки по итогам ревью — отдельный коммит с трейлером `Addresses-Review:` (скилл `pr-fix`).
- PR, исправляющий issue, помечает полноту: `Closes #N` — полностью (бот закроет issue при мерже), `Refs #N` / `Part of #N` — часть (issue остаётся открытой). Маркеры для одной issue не смешиваются; подробнее — «Ревью PR» в `AGENTS.md`.
- После правок кода → скилл `verify` (сборка, тесты, detekt). Для крупных изменений — субагент `architecture-reviewer`.
- Мерж в `master` — только после ревью: комментарий `/reviewed [sha]` владельца превращается в approve бота. Правила для агента — раздел «Ревью PR» в `AGENTS.md`.

| Задача | Скилл (`.claude/skills/`) |
|---|---|
| Новая фича целиком | `new-feature` |
| Машина, события, переходы, межфичевое взаимодействие | `state-machine` |
| Состояние экрана | `mvi-store` |
| Экраны, переходы, root, точки входа | `navigation` |
| Внедрение зависимостей | `di-metro` |
| UI-компоненты, токены, киты платформ | `design-system` |
| Тоглы | `feature-toggle` |
| Room / DataStore / репозитории | `data-storage` |
| HTTP | `network` |
| Агенты, инструменты, LLM-провайдеры | `ai-koog` |
| Управление компьютером инструментами `computer_*` (захват, ввод, расшифровка отказов) | `computer-use` |
| Логирование, аудит логов | `logging` |
| Gradle, build-logic, новый модуль, миграция шаблона | `module-setup` |
| Проверка перед «готово» | `verify` |
| Ревью PR и подпись `/reviewed` | `pr-review` |
| Исправление PR по ревью/комментариям/CI | `pr-fix` |
| Ревью + исправления до подписи (цикл) | `pr-review-and-fix` |

Субагенты (`.claude/agents/`): `feature-architect` (дизайн фичи до кода), `architecture-reviewer`, `ui-reviewer`, `test-writer`, `build-doctor`.
Хук `.claude/hooks/check-conventions.sh` проверяет каждый изменённый `.kt`/`.kts` (логи, корутины, цвета, границы модулей) и возвращает нарушения — исправляй сразу.
- Не добавляй библиотеки вне `gradle/libs.versions.toml` без согласования с пользователем.
- Документацию пиши в KDoc; правила для агентов — в `CLAUDE.md` / `AGENTS.md` (и подключаемых из них `.claude/rules/`, скиллах). Папку `docs/` не создавай.
