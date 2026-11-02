# Checkpoint: единая модель сессий — 02.11.2026

## Состояние передачи

- PR: https://github.com/aequicor/Heartbeat/pull/640 — **draft, фича не завершена**.
- Ветка: `codex/unified-session-model`, целевая ветка `master`.
- Свежий `origin/master` проверен при checkpoint: `b410de582c4a32a740d882d4ee986f24df9dd4a1` входит в HEAD через merge `4426537ae`.
- Последний слой этого checkpoint — atomic observation hold в `StudioRunSubmission` и его runtime-тесты. Код прошёл verify до коммита.
- Продолжать в этом же PR. Force push, merge PR и выпуск не разрешены. Пользователь поручил после реализации и регрессии перевести PR в ready через `open-pr`, затем выполнить `pr-review-and-fix`.
- Стандарт и макеты согласованы; повторного решения по дизайну не требуется, если реализация следует согласованным кадрам.
- Весь оставшийся объём ниже обязателен: foundation и зелёные unit-тесты не означают соответствие стандарту.

Источники: [стандарт v1](../rules/session-hierarchy.md), [основной план и аудит](session-model-unification.md), [правила проекта](../../CLAUDE.md), [превью](../../design/features/ai-studio/previews/session-model/README.md).
Стандарт не содержит миграцию; совместимость, миграция и последовательность работ находятся в плане разработки.

## Утверждённое поведение

1. Сессия — полноценный чат первого уровня под проектом.
2. Субсессия — созданный агентом полноценный чат второго уровня, со всеми пользовательскими и средовыми возможностями обычного чата. Family root и непосредственный creator — разные связи; вложенные создания не добавляют третий уровень sidebar.
3. Субагент — исполнитель своего проверенного владельца, отсутствует в sidebar. Пользователь читает историю/копирует и завершает; не пишет инструкции и не меняет модель. Коммуникация и запросы идут через создавший полный чат.
4. Background task не блокирует thinking после подтверждения запуска, остаётся выполняющимся tool call до реального terminal evidence, показывает доступный поток output при раскрытии и поддерживает только реальные действия движка.
5. ACK, исчезновение job/handle, конец родительского turn или наличие текста не подтверждают завершение. Unknown и unsupported нельзя заменять успехом. Модель должна одинаково трактоваться Heartbeat, оркестраторами и коннекторами.

UI-решения пользователя: единое ровное выделение строки; тонкая скобка группировки и контурный шеврон; настоящий dropdown `Субагенты · N` только текущего владельца; subtitle фактического хода; стандартный disabled composer без hint, с читаемой фактической моделью и поддерживаемым Stop; заголовок `Название сессии · Название субагента` — **разделительная точка, не звёздочка**.

## Что уже сделано

| Слой | Реализация и ограничение |
|---|---|
| Контракты | BackgroundTaskRef/attempt, монотонные revisions, phases/lifetime/output coverage/actions. Это пока контракт, не полная нормализация адаптеров. |
| Logical identity | Host-only logical context, root/creator/owner; canonical resolver, сохранение raw future/invalid metadata, отсутствие guessed rights. Old proven interactive helpers сохраняют human capability. |
| Полные субсессии | Профильный controller, durable admission/native-entry/stop fences и `subsession_spawn/send/status/result/wait/cancel`; новые старты под default-false `ai_studio.session_model`. Payload/очередь ещё требуют завершения. |
| Request provenance | Conversation/HelperWork/GraphWork/UnknownService, request-specific immutable policy/directives/model snapshot; точная helper lease attribution и recovery scheduler/harness без усыновления последнего независимого сообщения. |
| Human gates | Backend gates для отправки, модели/конфигурации/metadata, human queue, вопросов и планирования; валидный полный чат с потерянным creator не теряет human rights. Полные helper capabilities пока не доведены. |
| Context replacement | Общий route→journal barrier, проверка всех известных unresolved attempts и missing known evidence. Own unbound Conversation preparation переносится только по exact permit. |
| Graph native identity | Execution Q отделён от actual native R; recovery не создаёт Accepted Q на turn R, не применяет outside environment Q к R, не создаёт worktree Q при adoption. Checkpoint/result/cleanup используют actual R. |
| Recovery journal | Отдельный `recovery_<chat-hash>` в strict HelperAttemptsSpec, общий lock с send/stop. Frozen root/candidates/session, actual R/optional turn/outcome, monotonic stop; terminal-only не создаёт TurnId или send receipt. |
| Recovery publication | Explicit `StudioRecoveryRunHost`: pending reservation до публикации, route/journal вне coordinator lock, early Stop latch и persist-before-publish, captured Q не заменяется поздним request. Production Engine ещё не opt-in. |
| Recovery cancellation | Evidence и user Stop умеют идти по witness; legacy R без send receipt останавливается через exact adapter capability либо совпадающий live ref/request/turn. Missing/Unknown остаётся Unconfirmed; неполный ответ помечен. |
| Observation hold — этот checkpoint | Один CAS state в StudioRunSubmission: Recovering удерживает observer при Defer/Drop/caller detach; Retained не разрешает новый send; Submit перепроверяет отказ. Hooks вызываются до inspect и до admission; wrappers передают их. ensureActive стоит перед native open/admission. |
| UI foundation | Canonical hierarchy доходит до screen-model через versioned raw-kind envelope. Sidebar projection даёт только глубины 0/1. Фильтр скрытия Subagent и согласованный UI ещё не включены. |
| Дизайн | Согласованный `.pen` сохранён отдельно как `session-model.pen`, PNG в Git. Канонический `ai-studio.pen` содержит другие изменения master; не заменять его старым документом. |

Ключевые коммиты до последнего checkpoint: `005fb079c` contract, `bc77d2272` standard/plan, `f9ec9860a` design, `8c003f129` full control, `18b1968f8` tools, `dd3f0233b` exact work recovery, `3cf64ce89` context barrier, `df64761d6` origin, `1b499f478` actual native identity, `72383cb52` hierarchy projection, `d860a0df2` explicit continuation source, `7d5fa7f9a` Submit-only admission, `c04ecabe5` witness, `11eeb277c` staged publication, `e47000057` exact-native cancellation. Последний коммит checkpoint добавляет observation hold.

## Карта кода

- Data/runtime: `features/ai-studio/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aistudio/impl/data/`.
  Начать с `StudioSubmissionGate.kt`, `StudioRunCoordinator.kt`, `StudioTurnExecutor.kt`, `EngineStudioRepository.kt`, `StudioHelperAdmission.kt`, `StudioHelperAttempts.kt`, `StudioRecoveryWitness.kt`, `StudioRecoveryCancellation.kt`, `StudioConversationEvidence.kt`, `StudioConversationStops.kt`, `StudioHelperNative.kt`, `StudioGraphExecutions.kt`.
- Runtime tests: одноимённые файлы в `features/ai-studio/impl/src/commonTest/kotlin/io/aequicor/heartbeat/feature/aistudio/impl/data/`; hold-сценарии — `StudioNativeRecoveryTest` внутри `StudioTurnExecutorTest.kt`; есть `StudioRecoveryWitnessTest`, `StudioRecoveryReservationTest`, `StudioRecoveryCancellationTest`.
- Store projections: `features/ai-studio/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aistudio/impl/presentation/store/StudioNativeSessions.kt`, `SessionHierarchyMappings.kt`.
- UI: `features/ai-studio/screen-ui/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aistudio/screenui/`: `StudioScreenModels.kt`, `StudioPaneView.kt`, `StudioComposer.kt`, `StudioOrganismViews.kt`, `SessionRows.kt`.
- DTO/tree: `features/ai-studio/screen-model/src/commonMain/kotlin/io/aequicor/heartbeat/feature/aistudio/screenmodel/SidebarSessionTree.kt` и hierarchy/session DTO.
- Native/hosted contracts и adapters — `features/ai-engine/`; consumers — adaptive-subagents, scheduler, harness, organic AI. Использовать их project skills перед изменениями.

## Подробный оставшийся план

### 1. Закончить production recovery одним согласованным слоем

**Первый следующий шаг.** `EngineStudioRepository` пока не реализует `StudioRecoveryRunHost`, а `StudioHelperAdmission.requireNativeRecovery` сохраняет прежний запрет helper/Subsession adoption. Не снимать guard только потому, что unit foundation зелёный.

- Подключить `reserveRecovery`/`recordRecoveryStop`: witness до публикации Q, frozen canonical native ref/root/candidates; не брать route lock под coordinator lock. Разобрать pending engine switch до замораживания ref, не применяя настройки/environment Q к живому R.
- Добавить exact native bind/terminal/terminal-only/Unknown hooks в executor/host. Сначала сохранить actual identity в progress и journal, потом выполнять checkpoint/observer callbacks. Их ошибка не должна бросать уже принятый R или освобождать владение раньше native terminal proof.
- Journal recovery→Submit даже у ordinary root без helper. Сначала fresh native decision и проверка explicit stop, затем обычная Q admission; принятому/uncertain Q нельзя дать replay. При Cancelled proof сохранить actual R, при terminal-only не создавать TurnId.
- Закрывать NoSubmit только по exact local gate/sender proof, а не missing Q receipt. Hold отказ при Idle/Cancelled должен корректно завершить Q-подготовку, не оставить вечный Inspecting и не объявить R terminal без доказательства.
- Profile close сохраняет Unknown с имеющимся binding/stop, не synthetic Terminal/NotSubmitted. Новый observer после рестарта должен reconcile/передать observation ownership по тому же root/session и перенести stop; не удалять unresolved witness ради продолжения.
- Проверить cold Stop после исчезновения coordinator reservation: `record.lastRunRequest` может быть R, а witness хранится под Q. `.cancel(R)` не должен упасть в missing-receipt→NotSubmitted, если actual R известен через witness Q. Не ретаргетировать на произвольный current turn и не считать unordered candidates доказанным native binding.
- Довести snapshot/result до согласованного execution/native представления. Outcome/answer принадлежат actual R, не последнему сообщению чата; результат удерживается для владельца. Наличие текста не подтверждает завершение.

**Проверки:** Stop до публикации/во время bind/после bind, Defer/Drop/detach во время inspect, Idle без новой Q отправки при отказе, R==Q без replay, legacy R без receipt, cold Stop по original R, terminal-only, Unknown, callback/ACK failure, profile restart, native context сменился, stale Q не трогает новый независимый turn. После интеграции — read-only architecture review и verify.

### 2. Universal journal и полноценная continuation lineage

- Все новые Studio native turns, включая ordinary root, получают admission/entry journal и writer witness для отличия missing-new-evidence от legacy.
- Stop до handle fencing адресует exact reservation; legacy accepted native R без receipt использует только доказанный native fallback.
- `continuationOf` уже проходит WorktreeActionRequest→ExpectedAction→StudioTurnRequest→RunStarted. Это **ещё не immutable receipt lineage**.
- Добавить atomic prepareContinuation: сохранять policy/handoff/causes/model snapshot source, но не native identity/claims/terminal. Unknown/missing source не повышать до Conversation или work.
- Один validated resolver R→C→D для result/cancel/revoke/progress/recovery; запрет cycles, branching, смены owner/reservation/policy. Map virtualContinuations — только lookup hint.
- `lastRequest` остаётся actual C. Нужен narrow lineage-aware restore SPI, а не подмена metadata на R. Stop R до подготовки C должен запрещать C и не освобождать capacity до подтверждения остановки active descendant.
- Для legacy human source требовать совокупность canonical known human + exact human ledger + exact interrupted source + native terminal evidence; map-only/ledger-only недостаточны.

**Проверки:** Conversation→Conversation, Work→Work, независимый human после interruption, цепь R→C→D, cancel до C/при C, restore lease R при actual C, missing/invalid/unknown legacy, capacity до stop proof.

### 3. Полные возможности субсессий и durable creator queue

- Убрать постоянное ограничение по `isHelper` там, где canonical human capability и provenance разрешают planning/implement/compact/rollover/engine switch/autopilot.
- Не ослаблять OwnedHelperWork/harness restrictions для независимого chat turn и наоборот; живую профильную безопасность сохранить.
- Сохранять полный pending creator payload/origin/causes до enqueue, а не один fingerprint. Общий FIFO для human и agent-origin с правильным приоритетом/steering; agent-origin не становится human instruction ledger.
- Схемы и описания `subsession_send` должны обещать только реализованную очередь: сейчас preparation может вернуть Busy.

**Проверки:** одинаковые разрешённые действия root/Subsession, runtime/remote обходы запрещены, creator send переживает рестарт, retries не дублируют сообщения, human queue/steering master не регрессируют.

### 4. Явные Subagent writers и оркестраторы

- Подключить explicit role writers adaptive subagents, scheduler/graphs, harness, organic AI и native agents. Старый ScriptSessions.spawn с полным диалогом не переводить молча в executor.
- Проверенный полный чат — owner; parentless workflow остаётся InternalExecution. Native/biological parent не заменяет logical owner.
- Зигота сохраняет ID исходного полного чата, клетки/судьи — Subagent того владельца; биологическое дерево отдельно.
- Durable instance fence пользовательского завершения: queued delivery/recovery не возрождают этот экземпляр. Stop полноценной субсессии останавливает попытку, последующие явные sends остаются разрешены.
- UI, remote intents, tools, scripts, native routes не дают пользователю instruction/model/config mutation executor.

**Проверки:** все production creation paths, старые scripts/helpers читаются и сохраняют контракт, missing owner/future metadata не получают invented links/rights, завершённый executor не возвращается после restart.

### 5. Вопросы и permissions через владельца

- Exact mediation: owner/child logical IDs + native session/turn/request + nonce/options/arguments fingerprint и текущая ревизия.
- Проверять актуальность ответа; сменившийся/закрытый child не принимает stale answer. Не вводить boolean bypass и не отвечать за другой Native turn.
- Не переносить последнее независимое human сообщение владельца в старую work authority. Profile security остаётся общей и живой.

**Проверки:** пользователь не управляет диалогом executor напрямую, владелец может relay корректный вопрос, неверный owner/turn/nonce отклоняется, ответы после restart/stop безопасны.

### 6. Background tasks: hosted/native normalization и adapters

- Привязка исходного tool call/attempt/task handle создаётся до launch; durable origin/payload и монотонные revisions.
- Launch ACK возвращает управление thinking, но не завершает визуальный call. Native terminal/stop proof определяют исход; multi-child aggregate и lifetime учитываются явно.
- Bounded streaming stdout/stderr/output, coverage Complete/Partial/Unavailable/Unsupported, пропуски/ошибки/старые revisions не скрываются. История native checkpoint не переписывается: UI projection overlay поверх raw transcript.
- Реальные capabilities движков: observe/output/stop/reconnect после turn/profile restart. Не парсить обычный stdout/заголовок/текст модели как trusted lifecycle event.
- По `connect-engine` сверить закреплённые версии и официальные primary sources, добавить offline fixtures на каждый поддержанный протокол. Claude task_started/task_notification/run_in_background исследовались, но нормализация ещё не реализована. Unsupported объявлять явно.

**Проверки:** thinking продолжается при task active, output виден в call, lost ACK/job/parent turn end не становится terminal, retry/reconnect без копий, late output/stop/unavailable/aggregation, контракт каждого включённого engine.

### 7. Перенести согласованный UI атомарно

- Отдельный ownerAnchorId для sidebar/menu, реальный pane.sessionId executor для истории; missing owner не заменять family root.
- Объединить logical/native executor list только выбранного полного owner, счётчик из того же фильтра. Открытие executor сохраняет этот owner anchor.
- Одновременно скрыть Subagent во всех sidebar sections и дать menu/open/read-only viewer. Не прятать раньше, иначе потеряется единственная точка открытия.
- Header `Owner · Executor`; стандартный disabled composer без hint, фактическая модель и только доказанный Stop. Native SessionTrees содержит ref/parent/name/activity, **не model/stop capability**: не подставлять root model и не останавливать owner вместо child.
- Настоящий dropdown под/right кнопки, name + factual current-activity subtitle, отдельный Stop; единое shading, thin bracket, outline chevron, Desktop/Tablet/Phone, light/dark.
- Control predicates UI/store/backend одинаковы: canSubmit/canConfigure/canManageMetadata/canStop. Полные Subsession не ограничивать helper marker.

**Проверки:** Compose render snapshots 1280/834/390 по согласованным кадрам, typography/geometry/dark/light, keyboard/focus/dropdown/Stop; read-only UI review, сравнение с `.pen` и реальный smoke flow.

### 8. Завершение, регрессия, ready и review cycle

- Проверить весь стандарт, включая A01–A35, по текущим production paths; обновить KDoc и инструкции Heartbeat/движков. Отметить миграцию/совместимость в плане, не в стандарте.
- Применимые module compile/test + ordinary/typed detekt и DI на итоговом head; перечитать ограничения среды. Common/iOS проверки по verify/AGENTS; не расширять CI и не использовать его для локальной отладки.
- Изолированный HeartbeatE2E с deterministic mock по `e2e-regression`, без платных live LLM/CLI. Исправления в этом же PR, повтор затронутых сценариев.
- Итоговое architecture/UI review, актуальный master, каждый commit size, текущие PR checks. До этого **не ready**.
- Затем `open-pr` final description и ready. После — запрошенный пользователем `pr-review-and-fix`, до 3 раундов с verify на фактическом SHA; `/reviewed <full head>` только через выполненное pr-review. Merge/release не делать.

## Проверки checkpoint и ограничения

Последний полный локальный прогон:

```powershell
.\gradlew.bat :features:ai-studio:impl:detekt --auto-correct :features:ai-studio:impl:jvmTest :features:ai-studio:impl:detektMainJvm :platform-main:di-bundle:compileKotlinJvm --continue
```

`recovery-observation-hold-final3.log`: BUILD SUCCESSFUL, **1005/1005 Studio impl tests**, failures/errors/skips 0, DI и оба detekt успешны. Последние changes review — без блокеров. Gradle завершён, не перезапускать якобы потерянный live handle. `build/verification` и XML/reports — локальные ignored artifacts; для продолжения нужны команды/результаты здесь, а не эти файлы.

Ранее проверены: Studio API 110/110, screen-model 97/97, sidebar UI 6/6, worktree API 39/39, facade API 153, scheduler 228, harness focused 52. Это история проверок отдельных этапов, не свежий полный verify всей фичи.

Ограничения: typed-detekt выполняется успешно, но analysis имеет прежние generated serializer/inference diagnostics (Studio 3, screen-model 34; facade/scheduler/harness также есть старые ограничения). Это не полное type-analysis coverage. Full facade impl имел два baseline Windows symlink privilege failures и два skips — не объявлять весь suite зелёным. iOS/macOS и финальная app E2E ещё не проверены. Paid live CLI не запускались.

## Как продолжить на другой машине

1. Вызвать `$continue-pr https://github.com/aequicor/Heartbeat/pull/640`, прочитать актуальные PR description/head/checks и этот checkpoint. Не создавать другой PR или ветку.
2. Прочитать `AGENTS.md`, `CLAUDE.md`, применимые `.claude/rules/` и skills (как минимум state-machine, ai-koog, data-storage, verify; далее навыки конкретных consumers/UI).
3. Убедиться в clean checkout и фактическом head PR; fetch master и branch. Ничего не восстанавливать по локальным ignored файлам старой машины.
4. Начать с шага 1 production recovery выше; собрать полный вертикальный срез с тестами, не включать producer по одному наличию contracts. Далее строго сохранить шаги 2–8.
5. Каждый законченный шаг локально verify → staged size → commit → range size → push → актуальное PR description. Не переписывать опубликованную историю.

На Windows общий Git directory может быть вне worktree: для range checker с `--remerge-diff` нужен доступ к нему. Git иногда возвращает exit 0 и пустой diff вместе с `unable to create temporary object directory`; такой нулевой результат не PASS. Base sync измерять по manual remerge diff, не по всему унаследованному master. Перед commit всегда `python scripts/check_commit_size.py --staged`; перед push — `python scripts/check_commit_size.py --base origin/master --head HEAD`.

`.pen` зашифрован: использовать Pencil MCP, всегда точный путь `design/features/ai-studio/session-model.pen` своей рабочей копии. Не редактировать как JSON и не выбирать `ai-studio.pen`/другой worktree по активному окну. Не останавливать общие Gradle daemons: ими может быть запущено приложение пользователя.
