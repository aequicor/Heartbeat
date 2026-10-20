---
name: harness
description: "Харнессы Heartbeat (features:harness): библиотека окружений агента — скиллы, инструкции, шаблоны, Kotlin-скрипты и workflow, политика инструментов; машины библиотеки и запусков, desktop script host, хуки сессий, инструменты агента harness_*, раздел настроек. Используй при изменении API скриптов/workflow, событий, хуков, политики инструментов, script host или после апгрейда Kotlin."
---

# Харнессы: как менять фичу

Харнесс — окружение под задачу или проект, которое собирает агент. Пользователь включает,
выключает, подключает к чату, удаляет и редактирует тексты и код в
«Настройки → Харнесс». Тогл `harness.enabled` (по умолчанию true) включает фичу.
Разрешениями инструментов управляет общая профильная настройка «Безопасность».

## Где что лежит

| Что | Где |
|---|---|
| Модель, активация, лимиты, тоглы, маршруты, имена инструментов | `features/harness/api` (`Harness.kt`, `HarnessActivation.kt`, `HarnessLimits.kt`, `HarnessRoutes.kt`, `HarnessTools.kt`) |
| Машина библиотеки (`HarnessMachineKey`) и запусков (`HarnessRunsMachineKey`) | `harness/api` (`HarnessMachineSpec`, `workflow/HarnessRunsMachineSpec`) — таблицы переходов в KDoc |
| Публичный API скриптов и workflow, события | `harness/api` пакеты `script`, `workflow`, `event`; `HARNESS_API_VERSION` |
| Хранение (KV, журналы, доставки) | `harness/impl/data`, `domain/Harness*Storage` |
| Script host (Kotlin scripting, кэш, проба) | `harness/impl/src/jvmMain/.../data/script`; mobile — `UnsupportedHarnessScriptHost` |
| Рантайм скриптов (линия, хуки, события, таймеры, инструменты `hs_*`) | `harness/impl/domain/runtime`, `data/events`, `data/services` |
| Workflow (движок replay, помощники, восстановление) | `harness/impl/domain/workflow`, `data/run` |
| Инструменты агента `harness_*` и справочник | `harness/impl/data/authoring`, `domain/authoring` (`HarnessApiReference.kt`) |
| Доставка скиллов/инструкций, политика инструментов | `harness/impl/data/delivery` |
| Экраны настроек | `harness/impl/presentation`, `ui`; раздел — `settings` (`SettingsSection.Harness`) |
| Подключение из чата («+», чипы) | `ai-studio/impl` (`MachineStudioHarnesses`, `StudioTurnExecutor`) |
| Раздел `harnesses` конфигурации проекта (`.heartbeat`, `.hbai`) | `impl/domain/config/HarnessConfigCodec.kt` (раскладка файлов), `impl/data/config/HarnessConfigSection.kt` (проверка кода и политики, `Create`/`Update(Content)`) |
| UI-методы для интерфейсов service-as-ui (`ui.method`) | `harness/api/script/ScriptUi.kt`; `impl/domain/runtime/HarnessUiDispatch.kt` (`connectedAdmission`); провайдер `impl/data/serviceui/HarnessServiceUiProvider.kt` |
| Постоянные данные мини-приложения (`data.read/write/update`) | `api/script/ScriptData.kt`; `impl/domain/services/HarnessScriptData`, `impl/data/services/ProtectedHarnessData` |
| GitHub-порт скриптов (`github`, тогл `harness.github`) | контракты `api/github`; транспорт и сверка `impl/domain/github`, desktop gh `impl/data/github`; сборка activation-сервиса `data/services/HarnessScriptGitHubFactory` |
| Durable-процессы разработки (`processes.open/get/list/apply/remove`) | модель и переходы — `api/process` (`ProcessRecord`, `ProcessCommand`, `reduce`); сервис — `api/script/ScriptProcesses.kt`, `impl/domain/services/HarnessScriptProcesses` поверх `ScriptData` |
| Хуки сессий, политика, гейт нативных вызовов | `ai-engine/facade:{api,impl}` (`SessionHooks`, `ToolPolicy`, `authorizeNative`) |
| Адаптивные помощники (`AgentOptions.adaptive`, `sessions.spawn(..., adaptive)`) | `api/workflow/AdaptiveAgent.kt`; порт `impl/domain/services/HarnessHelperModels.kt`, адаптер над `AdaptiveModels` (`adaptive-subagents:api`) — `impl/data/services/AdaptiveHarnessHelperModels.kt` |
| Организм как этап workflow (`WorkflowScope.organism`) | `api/workflow/WorkflowOrganism.kt` (`OrganismOptions`, `OrganismOutcome`, `WorkflowOrganism`), `HarnessLimits.ORGANISM*`; `impl/domain/workflow/WorkflowOrganism{Driver,Resources,Journal,Host,Outcomes}.kt`; мост `impl/data/run/MachineOrganismHost.kt`, журнал выдачи слотов `impl/data/run/StoredWorkflowOrganismJournal.kt` |

## Правила

- Все изменяющие `harness_*` и агентские запуск/остановка workflow используют общий `ToolPermissions`.
  Full Trust не требует дополнительных разрешений; Automatic/Ask запрашивают подтверждение точного изменения.
  Код компилируется до сохранения; аргументы, содержимое, ревизия и подключения связываются одноразовым binding.
- Отдельные HarnessApproval, native-tools toggle и редактор tool-policy удалены. Старые policy-поля файлов инертны.
- Правки — `HarnessIntent.Internal.Edit`; другие фичи шлют только `Public.Attach`/`Detach`/`Reload`.
- Permission-хуки Deny/Ask в Ask/Automatic запрашивают подтверждение; Full Trust их не применяет. Контекстные хуки сохраняются.
- Харнессы активируются через сохранённое подключение к чату: пользователь выбирает их в меню «+» или
  агент вызывает `harness_attach`. Настройка `isDefaultForNewSessions` (изначально false) заранее выбирает
  включённый харнесс в новом обычном чате студии с учётом доступности в проекте. Пользователь может снять выбор,
  в том числе всех харнессов; подключения существующих чатов не меняются. Служебные и органические сессии
  не получают этот выбор. Сами области `Profile`/`Projects` автоматической активации не дают. Смена настройки
  сохраняет новую ревизию харнесса со штатной реактивацией кода, как другие правки его метаданных.
- Новый чат: харнессы, выбранные по умолчанию или в «+», передаются движку при открытии сессии
  (`CreateSessionRequest.preselected` → `AgentToolScope`/`ToolPolicyScope.preselected` под `HarnessTools.GROUP`),
  поэтому Codex объявляет их `hs_*` уже при `thread/start`; подключение записывается до первого промпта.
  Треду Codex, открытому раньше, харнесс, подключённый позже, новых `hs_*` не добавит — объявления заморожены.
  Если подключение до первого промпта не удалось, объявленные `hs_*` отвечают отказом, а hosted_off выбранного
  харнесса уже убрал инструменты из манифеста треда — ограничение остаётся, расширения нет.
- Лимит `ACTIVE_PER_SESSION` (8, по имени) ограничивает только вклад: контент, инструкции, инструменты скриптов,
  события, текстовые хуки и нативные On. Off/hosted_off и Deny/Ask-хуки `beforeTool` берутся от **всех**
  подходящих включённых харнессов (`applicableHarnesses`, `HarnessActiveAccess.applicable`,
  `HarnessSessionAdmission.restricts`) — пустые харнессы с ранними именами не вытесняют ограничения.
- Харнесс не может выключить инструменты `harness_*` и `hs_*` (иначе агент не починит харнесс).
- Одобрение скрипта покрывает его запуски workflow и помощников; помощники получают доверие Ask и общие квоты.
- Организм этапа (`WorkflowScope.organism`, тогл `organic_ai.enabled`) — owned-исполнитель крупного этапа. Результат —
  данные `OrganismOutcome` (`Answered` / `Refused` / `Ended`), не исключение; `Answered` не доказывает бизнес-успех.
  Идентичность организма выводит `workflowOrganism` из запуска и ключа шага: она сохраняется в `WorkflowStep.organism`
  и в выданном слоте (`WorkflowOrganismJournal`, `ORGANISMS_PER_RUN`/`ORGANISMS_PER_PROFILE`) до зачатия, поэтому
  повтор и рестарт сверяются с тем же организмом. Перед вызовом зачатия шаг переходит из `Prepared` в `Running`:
  неопределённая отправка не повторяется, исчезнувший организм даёт `Ended(Failed)`. Недоступность не доказывает
  отсутствие организма у восстановленного `Prepared` прежней версии. Слот возвращает только
  `WorkflowOrganismResources.release` после подтверждения окончания (`OrganismView.isEnded`); спящая или
  недоступная машина слот не освобождает. Как прерванный, так и успешно завершённый организм требуют
  `Organism.sessions == Released`. Ни записанный ответ, ни `Ended(Aborted)` сами по себе слот не возвращают;
  неподтверждённое освобождение повторяется с интервалом `ABORT_RETRY_POLLS`, готовый ответ сохраняется.
  Ожидание квоты подбирает подтверждённо законченные слоты отменённых parallel-веток; `Absent` для этого недостаточно,
  поскольку другая ветка могла ещё не отправить зачатие. Sleep/Awaken в одном профиле сохраняет неподтверждённые
  хендлы и opener. Освобождение проверяет все клетки, включая ранее убитые.
  Завершение или отмена запуска останавливают организмы (`WorkflowRunExecution.releaseOrganisms`),
  пауза — нет. Организм адресует только владелец шага через `OrganismHost`; его идентификатор не раскрывается автору,
  инструментам и другим сессиям. `harness:api` не зависит от `organic-ai:api`.
- UI-методы (`ui.method`) вызывает нажатие пользователя в интерфейсах service-as-ui, без хода агента; одобрение кода
  их покрывает. Допуск в чате — подключение включённого харнесса к этому чату (`connectedAdmission`, сессию передаёт
  доверенный хост), без чата — работающая активация. `ScriptUiResult.Error` и слишком большой результат не считаются
  сбоем скрипта, исключение — считается. Харнесс — владелец поверхностей через `HarnessServiceUiProvider`: полный
  снимок владельцев только в Ready (не suspended, не reloading, тогл включён), иначе поверхности не удаляются.
- Команды скриптов (`commands`, `ScriptCommands`; тогл `harness.commands`, по умолчанию true) — оффлайн мини-аппы:
  `define` только при вычислении скрипта (печать при публикации), программа и аргументы фиксированы в исходнике,
  параметр — только целый аргумент `{name}` с полным совпадением regex. `run` проверяет опубликованную активацию,
  тогл, платформу, область проекта харнесса (`HarnessCreationScope`, worktree → исходный проект) и
  `HarnessLimits.COMMAND_RUNS` на харнесс, запрещён из хуков; запуск — `LocalCommands` (`scheduler:api`).
  Unguarded-команды входят в авторизованную публикацию исходника: агентские изменения проходят общий gate,
  импорт конфигурации — явное решение пользователя. Нажатия, таймеры и события не требуют повторного разрешения.
  Это доверенный исполняемый код; контракт `commands` не изолирует произвольный Kotlin/JVM-код.
  Логируются только харнесс, итог и длительность, без программы, параметров и вывода.
- Защищённые команды процесса (`ScriptCommand.requiresDecision`, тогл `harness.process_actions`, default false) обычный
  `commands.run` не исполняет. `runForProcess` сам строит и сверяет точный снимок с текущим HEAD, GitHub origin,
  worktree, lifetime/attempt/input, approved source revision, argv и timeout; запрос и одноразовый Claim хранит хост
  отдельно от `ScriptData` в зашифрованном профильном журнале (256 записей / 1 МиБ, без вытеснения consumed identity).
  Решение — только нативный экран деталей харнесса. `ui_call`, callback, события, GitHub и `ProcessCommand.Grant`
  не одобряют запрос. Claim — необратимый допуск: после него отзыв не обещается; неопределённый исход требует сверки.
  Это exact-command consent, а не доказательство семантики authority: source review требует conditional external write
  с тем же полным SHA и отклоняет альтернативный unguarded/ProcessBuilder путь. Kotlin runtime не sandbox.
  Raw `ScriptData.write(hb_processes)` берёт общий processMutex; host processData пишет под уже взятым барьером.
- Логи: никогда не логируются исходники, тексты, payload, wake note, диагностика компилятора и сообщения
  исключений скриптов — только класс исключения. Частые события — `log.v` и `@HighFrequency`.
- Данные `ScriptData` принадлежат харнессу целиком и сохраняются в профильном `SecretStore`; ключ не содержит
  пользовательских сведений. Обновление исходника, detach и disable не очищают данные; удаление харнесса очищает
  его пространство. Доступ требует опубликованной действующей активации, пробная компиляция данных не читает.
  Запись атомарна по ожидаемой ревизии; `update` повторяет чистую функцию при конфликте. Квоты — `HarnessLimits`.
  `dataRevision` обновляется после успешной изменяющей записи, не после чтения или любого `ui.method`:
  автоисточники поверхностей тоже вызывают методы, общая инвалидизация каждого вызова создаёт цикл.
- Процессы (`processes`, `ScriptProcesses`) лежат в этом же пространстве одним конвертом под зарезервированным
  ключом `hb_processes`; напрямую через `data` его не трогаем. Открытие, изменение и удаление процесса — одна
  атомарная запись, поэтому нет индекса, который мог бы разойтись с записями, висячих идентификаторов и гонки
  `open`/`remove`. Нечитаемый конверт (новая версия, повреждение) — `IllegalStateException` без содержимого: он не
  пропускается и не перезаписывается, и недоступен весь набор; единственный выход — `data.write("hb_processes", null,
  revision)`, он удаляет все процессы (внешние системы сверить вручную). Ключ зарезервирован соглашением, а не
  границей безопасности. Квота пространства общая со своими данными скрипта: не вместившееся изменение —
  `IllegalArgumentException`. Внешняя запись проходит журнал: `BeginWrite` до обращения к системе, открытая запись
  помечается `MarkWriteUnknown(key, tries)`, сверяется и закрывается `ConfirmWrite` / `WriteNotApplied(key, tries)`
  (номер попытки берётся из записи; решение о прошлой попытке — `Unchanged`); слепой повтор невозможен, пока ключ в
  журнале (он помнит 8 ключей, поэтому ключ называет операцию: ревизия, head). `record.recovery(now, settleAfter)`
  вызывается в item-владельце процессов при активации и повторно по прошествии `settleAfter` (аренда вызова: больше
  самого долгого вызова или попытки без записи итога; `Activated` рестарт от замены исходника не отличает, а вызов
  прежней активации может ещё идти, поэтому моложе пункты не показываются); живая попытка (workflow) и актуальное
  ожидание остаются в списке. `processes.reconcileWait(id) { record, wait -> … }` проверяет внешнее состояние вне
  транзакции и применяет `ResumeChecked` со снимком lifetime, номера ожидания, revision и SHA: устаревшая сверка
  отклоняется, позднее событие не будит новое ожидание. Вызывай при Activated и событиях/таймерах; наблюдённые
  revision/SHA сначала сохрани. Сверка не запускает workflow, его deadline ожидание процесса не ограничивает.
  Номер попытки записи сквозной и не
  переиспользуется. Полномочия `Write`/`Publish`/`Merge`/`Release` выдаются
  командой `Grant` и по отдельности — это самодекларация скрипта, не одобрение человека и не граница безопасности.
- GitHub-порт (`github`, `api/github`) читает только фиксированные github.com маршруты, использует существующий
  логин gh и не раскрывает credentials. Каждая операция проверяет опубликованную активацию, запрещена из хуков
  и ограничена тоглом `harness.github` (по умолчанию false). Бизнес-стадии остаются в харнессе. Постоянный реестр
  `hb_github` в том же `ScriptData` резервирует immutable запрос до `BeginWrite` и внешнего вызова; 128 ключей,
  без вытеснения, общая квота байтов. `Unknown` допускает только чтение/сверку, отсутствие или неполная/неоднозначная
  страница не разрешает повтор. Ключи и корреляция сохраняются после вытеснения восьми слотов `ProcessRecord`.
  Допуск записи проверяется после API-очереди под общим processMutex; смена ревизии/закрытие не обгоняют отправку.
  Merge требует независимого host-owned допуска, самодекларации `ProcessAuthority` недостаточно;
  новые Dispatch/CreateRelease возвращают UnsafeReference до reservation/decision/send: branch/tag REST API не
  гарантирует атомарный expected SHA. Legacy unknown операции остаются read/reconcile only.
  `GitHubActionContext` связывает фактический workspace и process stage/attempt с типизированным payload, ключом,
  revision и SHA. Native decision хранится отдельно от ScriptData в общем с командами журнале #472; Pending не
  резервирует внешнюю запись. Claim выполняется после API-очереди с повторной проверкой scope/origin/HEAD и процесса;
  consumed identity не позволяет повтор даже после сброса hb_github. Завершает исходное решение только доказанный
  Confirmed/reconcile, а не возврат транспорта. Host Complete требует свежего remote evidence и полного исходного
  binding; script-owned cached result не считается host receipt. Локальный ConfirmWrite атомарно сверяет incarnation,
  key, authority и marker target. Нужны оба toggles harness.github и harness.process_actions.
  `GitHubObservation.current` недоступен при равновременном конфликте; persisted ambiguity разрешается только
  `refresh` из последовательно ожидаемого свежего чтения, не очередным event/cached page.
  Ни текст, ни маркер операции не являются разрешением.
  Приватные payload и идентификаторы скрыты в `toString`, тела и диагностики gh не логируются.
- UI (`impl/ui`) не импортирует `harness:api`: только презентационные модели (`presentation/HarnessUiModels.kt`).
- Тогл `harness.durable_processes` (default false) включает закрепление новых процессов и архив. Уже закреплённые
  процессы сохраняют защиту после его выключения; legacy-поведение незакреплённых записей при выключенном флаге
  совместимо с прежним. Процесс закрепляет `ProcessDefinition(item, revision)` одобренной активации; при смене кода новые этапы и resume
  отклоняются с `DefinitionMismatch`. Скрипт принимает версию явно через `MigrateDefinition(expected, next)` между
  этапами и без незавершённых внешних записей; существующие workflow/ключи/доказательства не переписываются.
  Legacy-записи читаются с `definition = null`, но продолжение требует такого же явного принятия версии.
  Неизвестная `schemaVersion` не читается и не перезаписывается. Завершение/отмена прежних работ и сверка внешних
  записей доступны при смене версии, чтобы миграция не блокировала cleanup.
- Архив процессов — отдельные immutable записи `ProtectedProcessArchives` в профильном `SecretStore`, вне квоты
  `ScriptData`; namespace харнесса их не удаляет. `ProcessArchiveReader` читает их и после удаления владельца.
  `ProcessArchiveRef.asResultRef()` — устойчивая ссылка на доказательство внутри того же профиля. Исходный JSON
  снимка сохраняется как есть, поэтому смена порядка сериализуемых полей не ломает старые ссылки. Запись повторяема
  по digest владельца/содержимого/причины; перед успехом проверяется чтением. Лимит — 1024 снимка на профиль,
  до 24 КиБ исходного процесса каждый, страницы до 100 записей; переполнение даёт ошибку без вытеснения доказательств.
  Удержание постоянное до явного wipe профиля, без таймеров/самодельной чистки. Идентификаторы и содержимое не логируются.
- `processes.archive/readArchive/archived/restore` доступны только текущей активации и своему владельцу.
  `remove` и вытеснение завершённой записи при полном `open` подтверждают архив перед удалением точного снимка
  активной записи; сбой/переполнение сохраняет оригинал. Native cleanup-квитанция `ProcessWorkflow.isSettled`
  записывается хостом (`SettleWorkflow`, не ScriptProcesses.apply) до освобождения журнала workflow; она переживает
  его retention, поэтому давно завершённые этапы не блокируют архивирование процесса.
  Восстановленный Running сохраняет isDispatchClosed: без native journal повторный dispatch запрещён,
  get/cancel возвращают Unavailable, а Finish(Cancelled) не подменяет квитанцию остановки.
  Удаление харнесса сначала архивирует все его процессы, затем фиксирует удаление библиотеки; orphan cleanup
  повторяет копирование до удаления namespace. Restore сохраняет исходные lifetime, счётчики, ожидания и workflow
  keys, не запускает исполнителей, не откатывает уже живое состояние того же lifetime и отклоняет чужой active lifetime.
  Открытые попытки/внешние записи требуют сверки; смена кода — явной миграции. Архив сохраняет ProcessRecord и ссылки
  на доказательства; внешние результаты принадлежат их системе (публичные — GitHub), произвольные ScriptData не архивируются.
  Presentation видит только порты `domain` (`domain/authoring/HarnessLibraryPorts.kt`), не `data`. Навигацию
  экранов выполняют компоненты по `MVIAction` сторов; retained-модели навигатор не держат. Экран повторяет
  неудачную загрузку библиотеки через `Public.Reload` (в `Failed` без приостановки — новая загрузка).
- Выбор харнессов нового чата живёт в сторе студии; при отправке он заявляется по submissionId
  (`StudioHarnesses.claim`) и подключается до первого промпта; ходы ждут только при заявке в полёте.
  До первого известного снимка библиотеки отправка из нового обычного чата недоступна, черновик сохраняется;
  ошибка загрузки показывает повтор через `Public.Reload`. Выключенная фича даёт известный пустой снимок.

## Изменение API скриптов и workflow

1. Меняй интерфейсы в `harness/api` пакетах `script`/`workflow`/`event`; базовые классы только
   получают зависимости и регистрируют определения, без IO.
2. Подними `HARNESS_API_VERSION` при любом несовместимом изменении: он входит в ключ кэша компиляции,
   старые jar перекомпилируются.
3. Обнови `defaultImports` в `HarnessCompilerConfiguration.kt`, если новый тип должен быть доступен без импорта
   (поимённо: `Duration.Companion.*` нельзя), и keep-правила ProGuard в `platform-main/desktop` для новых пакетов.
4. Обнови справочник `domain/authoring/HarnessApiReference.kt` (темы guide/api/events/examples).
   Примеры `EXAMPLE_VERIFY_SCRIPT` и `EXAMPLE_SCREEN_WORKFLOW` компилирует `HarnessReferenceExamplesTest` (jvmTest) —
   он должен оставаться зелёным.
5. Новое событие: тип в `harness/api/event`, источник в `data/events/HarnessEventSources.kt`, маппинг и тест.

Адаптивные помощники: модель выбирается один раз до `acquire` слота (`WorkflowHelperDriver.prepare`,
`HarnessSpawnOperations.produce`) и передаётся в `HelperAgents.create` как target + `HelperModelOptions(effort)`;
явный `target` главнее, восстановленный шаг или помощник не выбирают заново. Digest шага workflow меняется только
при `adaptive != null` — старые журналы не расходятся. `Inherit` (фича выключена, нет кандидатов) оставляет
прежнее поведение; ревью действий помощника идёт на модели родителя (хост помощника задаёт `reviewTarget`).

## После апгрейда Kotlin

Скрипты компилирует K2 (`kotlin-scripting-jvm-host`) в процессе-воркере (`data/script/worker`, запуск —
`ProcessHarnessCompiler`), а исполняются они в процессе приложения. В релизном установщике тяжёлых библиотек
компилятора нет: первое изменение исходника скачивает шесть оригинальных Maven JAR через `VerifiedArtifactCache`.
Worker перед загрузкой повторно проверяет имя, размер и SHA-256 каждого файла. Валидный кэш скомпилированного
кода исполняется офлайн без подготовки компилятора.

При смене версии Kotlin обнови `HarnessCompilerArtifacts`: версию, имена, размеры и SHA-256 всех шести оригинальных
JAR, а также защиту версии в `features/harness/impl/build.gradle.kts`. Не бери хеши JAR после ProGuard. Закреплённый
набор входит в ключ кэша скриптов; несовпадение с каталогом блокирует конфигурацию сборки. Затем выполни:

```bash
./gradlew :features:harness:impl:jvmTest
./gradlew :platform-main:desktop:verifyReleaseHarnessCompiler
```

Вторая задача собирает приложение и передаёт пробе `--heartbeat-harness-probe` шесть закреплённых оригинальных
JAR из Gradle, поэтому проверка не зависит от сети. Проба компилирует и исполняет скрипт и workflow в релизной
раскладке (ProGuard, jlink), компилятор — в упакованном воркере. При ручном вызове флага в тонкой релизной сборке
передай абсолютные пути всех шести JAR в порядке `HarnessCompilerArtifacts.artifacts`.
Ненулевой код — смотри `HarnessHostProbe.kt` и keep-правила. На другой ОС пробу собирает `module-build.yml` с задачей
`:platform-main:desktop:verifyReleaseHarnessCompiler` — сборка приложения целиком, только по просьбе пользователя.

## Проверка

```bash
./gradlew :features:harness:api:jvmTest :features:harness:impl:jvmTest
./gradlew :features:harness:impl:detekt :features:harness:api:detekt
./gradlew :platform-main:di-bundle:jvmTest
```
