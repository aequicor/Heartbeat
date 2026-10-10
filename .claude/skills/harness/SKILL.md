---
name: harness
description: "Харнессы Heartbeat (features:harness): библиотека окружений агента — скиллы, инструкции, шаблоны, Kotlin-скрипты и workflow, политика инструментов; машины библиотеки и запусков, desktop script host, хуки сессий, инструменты агента harness_*, раздел настроек. Используй при изменении API скриптов/workflow, событий, хуков, политики инструментов, script host или после апгрейда Kotlin."
---

# Харнессы: как менять фичу

Харнесс — окружение под задачу или проект, которое собирает агент. Пользователь включает,
выключает, задаёт область, подключает к чату, удаляет и редактирует тексты и код в
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

- Подтверждения агента: уровень `HarnessApproval` покрывает тексты, метаданные, область, подключение и
  выключение инструментов. Код (скрипты, workflow) и включение нативных инструментов агент подтверждает
  у пользователя **всегда**. Вопрос показывает весь текст; binding привязывает уровень и ревизию харнесса,
  машина применяет правку только к этой ревизии. Код компилируется до вопроса; при ошибке ничего не сохраняется.
- Хуки только ужесточают (Deny / Ask / контекст). Вердикта «разрешить» нет.
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

Скрипты компилируются в процессе приложения (`kotlin-scripting-jvm-host`, K2). После смены версии Kotlin:

```bash
./gradlew :features:harness:impl:jvmTest
./gradlew :platform-main:desktop:createReleaseDistributable
```

Затем запусти собранное приложение с `--heartbeat-harness-probe` и проверь код выхода 0: проба компилирует и
исполняет скрипт и workflow в релизной раскладке (ProGuard, jlink). Ненулевой код — смотри `HarnessHostProbe.kt`
и keep-правила.

## Проверка

```bash
./gradlew :features:harness:api:jvmTest :features:harness:impl:jvmTest
./gradlew :features:harness:impl:detekt :features:harness:api:detekt
./gradlew :platform-main:di-bundle:jvmTest
```
