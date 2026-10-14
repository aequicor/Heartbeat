---
name: harness
description: "Харнессы Heartbeat (features:harness): библиотека окружений агента — скиллы, инструкции, шаблоны, Kotlin-скрипты и workflow, политика инструментов; машины библиотеки и запусков, desktop script host, хуки сессий, инструменты агента harness_*, раздел настроек. Используй при изменении API скриптов/workflow, событий, хуков, политики инструментов, script host или после апгрейда Kotlin."
---

# Харнессы: как менять фичу

Харнесс — окружение под задачу или проект, которое собирает агент. Пользователь включает,
выключает, подключает к чату, удаляет и редактирует тексты и код в
«Настройки → Харнесс». Тоглы: `harness.enabled` (вся фича) и `harness.native_tools`
(`facade:api`, включение нативных инструментов движков); оба по умолчанию false.

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
| UI-методы для интерфейсов service-as-ui (`ui.method`) | `harness/api/script/ScriptUi.kt`; `impl/domain/runtime/HarnessUiDispatch.kt` (`connectedAdmission`); провайдер `impl/data/serviceui/HarnessServiceUiProvider.kt` |
| Постоянные данные мини-приложения (`data.read/write/update`) | `api/script/ScriptData.kt`; `impl/domain/services/HarnessScriptData`, `impl/data/services/ProtectedHarnessData` |
| Хуки сессий, политика, гейт нативных вызовов | `ai-engine/facade:{api,impl}` (`SessionHooks`, `ToolPolicy`, `authorizeNative`) |

## Правила

- Все изменяющие `harness_*` и агентские запуск/остановка workflow проходят автоматическую проверку через
  `AgentSafetyReviewer` фасада. Код сначала компилируется; safe разрешает точный снимок, risk/uncertain/сбой
  возвращают отказ с причиной агенту без диалога. `HarnessApproval` читается для совместимости старых данных,
  но не управляет этим гейтом. Доверие сессии не заменяет проверку безопасности.
- Снимок включает полный старый и новый харнесс, политику, подключения, caller/model, аргументы и ревизию;
  `approval` пересчитывает тот же binding, `execute` только читает memo. Машина применяет запись при совпадении
  ожидаемых ревизии и подключений. Ревью проходит в отдельной сессии без проекта, инструментов и харнессов.
- Правки содержимого, кода и включения — только `HarnessIntent.Internal.Edit` (сторы фичи и инструменты
  агента после автоматической проверки). Другие фичи шлют только `Public.Attach`/`Detach`/`Reload`;
  у `HarnessRunsMachineKey` публичных интентов нет.
- Хуки только ужесточают (Deny / Ask / контекст). Вердикта «разрешить» нет.
- Харнессы активируются только явным подключением к чату: пользователь выбирает их в меню «+» или
  агент вызывает `harness_attach`. Старые области `Profile`/`Projects` сохраняются для чтения данных, но не
  дают автоматической активации.
- Новый чат: харнессы, выбранные в «+», передаются движку при открытии сессии
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
- UI-методы (`ui.method`) вызывает нажатие пользователя в интерфейсах service-as-ui, без хода агента; одобрение кода
  их покрывает. Допуск в чате — подключение включённого харнесса к этому чату (`connectedAdmission`, сессию передаёт
  доверенный хост), без чата — работающая активация. `ScriptUiResult.Error` и слишком большой результат не считаются
  сбоем скрипта, исключение — считается. Харнесс — владелец поверхностей через `HarnessServiceUiProvider`: полный
  снимок владельцев только в Ready (не suspended, не reloading, тогл включён), иначе поверхности не удаляются.
- Логи: никогда не логируются исходники, тексты, payload, wake note, диагностика компилятора и сообщения
  исключений скриптов — только класс исключения. Частые события — `log.v` и `@HighFrequency`.
- Данные `ScriptData` принадлежат харнессу целиком и сохраняются в профильном `SecretStore`; ключ не содержит
  пользовательских сведений. Обновление исходника, detach и disable не очищают данные; удаление харнесса очищает
  его пространство. Доступ требует опубликованной действующей активации, пробная компиляция данных не читает.
  Запись атомарна по ожидаемой ревизии; `update` повторяет чистую функцию при конфликте. Квоты — `HarnessLimits`.
  `dataRevision` обновляется после успешной изменяющей записи, не после чтения или любого `ui.method`:
  автоисточники поверхностей тоже вызывают методы, общая инвалидизация каждого вызова создаёт цикл.
- UI (`impl/ui`) не импортирует `harness:api`: только презентационные модели (`presentation/HarnessUiModels.kt`).
  Presentation видит только порты `domain` (`domain/authoring/HarnessLibraryPorts.kt`), не `data`. Навигацию
  экранов выполняют компоненты по `MVIAction` сторов; retained-модели навигатор не держат. Экран повторяет
  неудачную загрузку библиотеки через `Public.Reload` (в `Failed` без приостановки — новая загрузка).
- Выбор харнессов нового чата живёт в сторе студии; при отправке он заявляется по submissionId
  (`StudioHarnesses.claim`) и подключается до первого промпта; ходы ждут только при заявке в полёте.

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

## После апгрейда Kotlin

Скрипты компилирует K2 (`kotlin-scripting-jvm-host`) в процессе-воркере (`data/script/worker`, запуск —
`ProcessHarnessCompiler`), а исполняются они в процессе приложения. После смены версии Kotlin:

```bash
./gradlew :features:harness:impl:jvmTest
./gradlew :platform-main:desktop:createReleaseDistributable
```

Затем запусти собранное приложение с `--heartbeat-harness-probe` и проверь код выхода 0: проба компилирует и
исполняет скрипт и workflow в релизной раскладке (ProGuard, jlink), компилятор — в упакованном воркере.
Ненулевой код — смотри `HarnessHostProbe.kt` и keep-правила. На другой ОС пробу собирает `module-build.yml` с задачей
`:platform-main:desktop:verifyReleaseHarnessCompiler` — сборка приложения целиком, только по просьбе пользователя.

## Проверка

```bash
./gradlew :features:harness:api:jvmTest :features:harness:impl:jvmTest
./gradlew :features:harness:impl:detekt :features:harness:api:detekt
./gradlew :platform-main:di-bundle:jvmTest
```
