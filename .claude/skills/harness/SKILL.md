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
| Хуки сессий, политика, гейт нативных вызовов | `ai-engine/facade:{api,impl}` (`SessionHooks`, `ToolPolicy`, `authorizeNative`) |

## Правила

- Подтверждения агента: уровень `HarnessApproval` покрывает тексты, метаданные, подключение и
  выключение инструментов. Для этих правок `TrustLevel.Full` («Подтверждать за меня») имеет приоритет
  над профильным уровнем, включая `Ask`; `AutoEdits` сам по себе их не подтверждает.
  **Всегда** спрашивают: код (скрипты, workflow); включение нативных инструментов;
  снятие Off/hosted_off; отключение, удаление и сужение харнесса с ограничениями или включённым скриптом;
  удаление включённого скрипта; подключение и расширение харнесса с нативным On или включённым скриптом.
  Вопрос показывает весь текст; binding привязывает уровень и ревизию харнесса (для `harness_tools_set` — и
  дайджест итоговой политики), машина применяет правку только к этой ревизии. Код компилируется до вопроса;
  при ошибке ничего не сохраняется. Отказ, который ещё спрашивает, показывает «Request will be refused: …».
- Правки содержимого, кода, включения и уровня — только `HarnessIntent.Internal.Edit` (сторы фичи и инструменты
  агента после решения пользователя). Другие фичи шлют только `Public.Attach`/`Detach`/`Reload`;
  у `HarnessRunsMachineKey` публичных интентов нет.
- Хуки только ужесточают (Deny / Ask / контекст). Вердикта «разрешить» нет.
- Харнессы активируются только явным подключением к чату: пользователь выбирает их в меню «+» или
  агент вызывает `harness_attach`. Старые области `Profile`/`Projects` сохраняются для чтения данных, но не
  дают автоматической активации.
- Лимит `ACTIVE_PER_SESSION` (8, по имени) ограничивает только вклад: контент, инструкции, инструменты скриптов,
  события, текстовые хуки и нативные On. Off/hosted_off и Deny/Ask-хуки `beforeTool` берутся от **всех**
  подходящих включённых харнессов (`applicableHarnesses`, `HarnessActiveAccess.applicable`,
  `HarnessSessionAdmission.restricts`) — пустые харнессы с ранними именами не вытесняют ограничения.
- Харнесс не может выключить инструменты `harness_*` и `hs_*` (иначе агент не починит харнесс).
- Одобрение скрипта покрывает его запуски workflow и помощников; помощники получают доверие Ask и общие квоты.
- Логи: никогда не логируются исходники, тексты, payload, wake note, диагностика компилятора и сообщения
  исключений скриптов — только класс исключения. Частые события — `log.v` и `@HighFrequency`.
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
Ненулевой код — смотри `HarnessHostProbe.kt` и keep-правила.

## Проверка

```bash
./gradlew :features:harness:api:jvmTest :features:harness:impl:jvmTest
./gradlew :features:harness:impl:detekt :features:harness:api:detekt
./gradlew :platform-main:di-bundle:jvmTest
```
