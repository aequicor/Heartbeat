---
name: service-as-ui
description: "Интерфейсы агента Heartbeat (features:service-as-ui): библиотека поверхностей, декларативная спека v1 (узлы, действия, привязки, валидатор, лимиты), места студии (слоты, панели доков, модалки), карточки ответов, инструменты ui_*, методы владельцев (SPI ServiceUiProvider, ui.method харнессов), web-документы на Desktop через BrowserDocuments. Используй при изменении спеки, мест, карточек, инструментов ui_* или web-узлов."
---

# Интерфейсы агента: как менять фичу

Агент собирает поверхности (`UiSurface`) — декларативные экраны из узлов спеки — и кладёт их в библиотеку профиля;
пользователь нажимает их кнопки без хода агента, кнопки вызывают методы владельца (сейчас — харнесса, `ui.method`).
Тоглы: `service_ui.enabled` (вся фича) и `service_ui.web` (узлы `web`, только Desktop); оба по умолчанию false.

## Где что лежит

| Что | Где |
|---|---|
| Библиотека, владельцы, места, лимиты, тогл `service_ui.enabled`, маршруты, имена инструментов | `service-as-ui/api` (`ServiceUiLibrary.kt`, `ServiceUiIds.kt`, `ServiceUiCalls.kt`, `ServiceUiLimits.kt`, `ServiceUiRoutes.kt`, `ServiceUiTools.kt`); тогл `service_ui.web` — `impl/domain/ServiceUiToggles.kt` |
| Профильная машина `ServiceUiMachineKey` | `api/ServiceUiMachineSpec.kt` — таблица переходов в KDoc, тесты в `api/src/commonTest` |
| SPI владельцев и методов | `api/spi/ServiceUiProvider.kt` — только реализующие модули (`harness:impl`) и `di-bundle` |
| Спека v1: узлы, действия, привязки `{{path}}`, разбор, валидатор, справочник агента | `impl/domain/spec` (`UiNode`, `UiAction`, `UiTemplate`, `UiSpecCodec`, `UiSpecValidator`, `UiSpecReference`) |
| Хранение: библиотека (KV `service_ui_library`), карточки (Room `service_ui`, `ui_cards`) | `impl/data` (`KeyValueUiLibrary`, `RoomUiCards`, `ServiceUiDatabase`) |
| Вызовы методов (таймаут, лимиты, лог) | `impl/data/ProviderUiMethodCalls.kt`, порт `domain/UiMethodCalls.kt` |
| Вычисление спеки и стор поверхности | `impl/presentation` (`UiEvaluator`, `UiValues`, `store/SurfaceModel`, `store/SurfaceWebDocuments`) |
| Места (слот/панель/модалка), карточки, библиотека, редактор | `impl/presentation/{place,card,library,editor}`, компоненты `presentation/component`, экраны `impl/ui` |
| Инструменты `ui_reference/list/get/put/delete/show/call` | `impl/data/tools` |
| Файлы и даты платформы | `domain/UiPlatform`; `DesktopUiPlatform` (jvm), `MobileUiPlatform` (Android/iOS) |
| Web-документы | `domain/UiWebDocuments` → `data/BrowserUiWebDocuments` (`browser:api` `BrowserDocuments`); хосты `presentation/component/WebDocumentHosts` |
| Студия: слоты композера и старта, панель дока, карточки в ленте | `ai-studio/impl` (`MachineStudioSurfaces`, `StudioSurfaceBinding`, `StudioSidePanel`, `AiStudioComponent`) |
| Раздел настроек «Интерфейсы» | `settings` (`SettingsSection.Interfaces` → `ServiceUiLibraryRoute(isEmbedded = true)`) |

## Правила

- Наружу уходит только исходный JSON спеки (`UiSurfaceContent.spec`); модель спеки — внутренняя `impl`. Агентский JSON
  перед kotlinx проверяется на вложенность по сырому тексту (`UiSpecLimits.JSON_DEPTH`): `parseToJsonElement`
  рекурсивен и падает на глубоком вводе.
- Неизвестные ключи и типы спеки — ошибка разбора с путём JSON; новая функция спеки — новый узел/действие в
  `domain/spec`, ветка во всех исчерпывающих `when` (без `else`), правило валидатора, текст `UiSpecReference`, тесты
  разбора, валидатора и вычисления.
- Привязки — только `{{path}}` без выражений; карточки без владельца (inline `ui_show`) не вызывают методы, не грузят
  данные, не отправляют промпт и не получают мост web.
- Правки библиотеки — `ServiceUiIntent.Internal.Edit` с ожидаемой ревизией и одним слотом записи (`Busy` — повтор
  вызывающим); хосты (студия) шлют только `BindHost`/`UnbindHost`/`OpenPanel`/`ClosePanel`/`Reload`.
- Поверхности удалённого владельца удаляются только по полному снимку провайдера (`UiOwners.isComplete`).
- Подтверждения: `ui_put`/`ui_delete` — Edit (спека целиком в вопросе, binding `ui_put:<id>@<rev>:<sha256>`),
  поверхность с `web` спрашивает всегда; `ui_show` — Read, `ui_call` — Command; нажатия пользователя — без вопроса.
- Логи: никогда не логируются спеки, HTML, данные, аргументы, результаты, URL, пути и тексты промптов; вызовы — INFO с
  владельцем, методом, исходом и длительностью; сбои провайдеров — `redactedFailure()`.
- UI (`impl/ui`) не импортирует api: презентационные модели (`UiNodeUi` и др.) `@Immutable`, отображения исчерпывающие.

## Web-документы

- Узел `web` (`html`, `bridge`, `data`) — только поверхности Panel/Modal и модалки; один на поверхность.
  `SurfaceWebDocuments` открывает документ через `UiWebDocuments`, пока узел нарисован и включён `service_ui.web`;
  новый HTML/мост — новый документ, новая `data` — `update`, остановка стора — `close`. Мост — только поверхности с
  владельцем, не карточки и не превью редактора: страница вызывает методы владельца объектом аргументов.
- Место, карточка и превью держат стеки `BrowserDocumentRoute` (`WebDocumentHosts`); узел рисует документ высотой
  `HbDimensions.webDocumentHeight`, без документа — заметку.
- Изоляция — в `browser:impl` (`DesktopDocument*`): свой origin `https://<id>.document.heartbeat.invalid/`, контекст в
  памяти, CSP без соединений, запросы кроме самого документа отклоняются, WebRTC — UDP только через прокси (его
  нет), конструкторы WebRTC удалены из окна страницы. TURN по TCP из дочернего фрейма закроет прокси контекста на
  закрытый порт: нужен рантайм hb3, где JCEF передаёт словарные настройки (`native/jcef/README.md`). Вызовы страницы —
  свой лимит (1) и лог trace; только Desktop. Проверка с настоящим Chromium —
  `./gradlew :features:browser:impl:browserDocumentNativeTest` (локально, нужен рабочий стол).

## Проверка

```bash
./gradlew :features:service-as-ui:api:jvmTest :features:service-as-ui:impl:jvmTest
./gradlew :features:service-as-ui:impl:detekt :features:service-as-ui:impl:detektMainJvm
./gradlew :platform-main:di-bundle:jvmTest
```
