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
- Назначение `card` — каноническая мини-апп: компактная карточка через `ui_show`, кнопка в composer,
  полный Start для выбранного владельца и просторная модалка. Один `ServiceUiAppRoute` принадлежит pane вне lazy-ленты.
  `AppHostModel` удерживает одну SurfaceModel и документ; same-owner Composer/Start-зеркала скрываются.
  Приложения загружают автоисточники и получают мост владельца; редакторское превью не выполняет
  методы даже с указанным владельцем. Не определяй полномочия одним `isCard` или наличием owner.
- Успешная запись данных харнесса меняет `ServiceUiProvider.dataRevision`: поверхности перезагружают только
  автоисточники. Чтение не инвалидирует данные; устаревший ответ не должен перетирать новый. `focusCard` ищет
  приложение своей поверхности и открывает его; прокрутка к карточке остаётся fallback. Контекст перепроверяется.
- Правки библиотеки — `ServiceUiIntent.Internal.Edit` с ожидаемой ревизией и одним слотом записи (`Busy` — повтор
  вызывающим); хосты (студия) шлют только `BindHost`/`UnbindHost`/`OpenPanel`/`ClosePanel`/`Reload`.
- Поверхности удалённого владельца удаляются только по полному снимку провайдера (`UiOwners.isComplete`).
- `ui_put`/`ui_delete`/`ui_show`/`ui_call` проходят независимую автоматическую проверку безопасности через
  `AgentSafetyReviewer`: полный снимок, аргументы, caller/model, ревизии. Safe разрешает только этот binding;
  risk/uncertain/сбой возвращают отказ агенту без вопроса пользователю. `approval` только пересчитывает снимок,
  исполнение читает memo и повторно проверяет актуальность. Политика и хуки могут запретить, но не обойти ревью.
- Агентский `ui_call` получает исходник и непрозрачный binding текущего callback через `ServiceUiProvider.review`.
  Провайдер повторно проверяет binding сразу перед callback после очереди. Рестарт с тем же исходником и повторная
  регистрация метода меняют binding. Нажатия пользователя используют уже проверенный код без нового ревью.
- Логи: никогда не логируются спеки, HTML, данные, аргументы, результаты, URL, пути и тексты промптов; вызовы — INFO с
  владельцем, методом, исходом и длительностью; сбои провайдеров — `redactedFailure()`.
- UI (`impl/ui`) не импортирует api: презентационные модели (`UiNodeUi` и др.) `@Immutable`, отображения исчерпывающие.

## Web-документы

- Узел `web` (`html`, `bridge`, `data`, `height`) — библиотечные поверхности, включая Card/Composer, и модалки;
  один на поверхность. Анонимные карточки сохраняют прежние ограничения.
  `SurfaceWebDocuments` открывает документ через `UiWebDocuments`, пока узел нарисован и включён `service_ui.web`;
  новый HTML/мост/режим Content — новый документ, новая `data` — `update`, остановка стора — `close`. Мост — только поверхности с
  владельцем, включая библиотечные карточки, но не превью редактора: страница вызывает методы владельца
  объектом аргументов. Скрытие канонической мини-аппы сохраняет документ, DOM, вкладку и незаписанный ввод;
  перенос Start→чат того же владельца отзывает старые вызовы и сохраняет документ. Чужой контекст/спека/владелец
  освобождают его. Значимое состояние и таймеры хранятся в харнессе, не только в DOM.
- Место, карточка и превью держат стеки `BrowserDocumentRoute` (`WebDocumentHosts`); узел рисует документ высотой
  токенами `HbDimensions.webDocumentCompactHeight` / `webDocumentHeight` / `webDocumentTallHeight`
  (спека `compact` / `regular` / `tall`). `content` измеряет body или `[data-heartbeat-root]` в пределах токенов.
  Полная мини-аппа использует `BrowserDocumentPresentation.Content`: Start задаёт точную высоту доступной области,
  а модалка следует измеренной высоте содержимого с ограничением экраном, включая уменьшение после смены вкладки.
  Preset не ограничивает полный вид. Один web-node получает оставшийся предел высоты и прокручивается сам,
  без внешнего scroll; чисто native body имеет один общий scroll.
  У модалки с корневым `web` нет внутренней рамки, отступов и отдельной шапки: документ занимает всю поверхность со скруглением,
  закрытие наложено справа сверху. `--hb-overlay-width` / `--hb-overlay-height` задают занятую область в CSS px
  (вне этого режима — 0). HTML учитывает её в своей верхней панели, не добавляет пустую полосу на всю ширину.
  HTML содержит один заголовок и responsive-контент, без фиксированных высот страницы и вложенных overflow.
  Измерения принадлежат конкретному native view и поколению страницы; отдельный канал размеров не даёт права
  вызова методов владельца. Без документа — заметка.
- HTML получает `--hb-*` CSS-токены и `heartbeat.theme` / событие `heartbeat:theme` из текущей темы хоста.
  Изменение темы обновляет DOM без перезагрузки и не меняет данные. Пропорциональный системный sans для интерфейса,
  monospace только для кода; один явный primary action, плотный контент, тихие tabs и естественные empty states.
  Пользовательская стилистика — сдержанный акцент/иллюстрация, не отдельный sidebar, neon pills или пустой full-screen.
- Доступный viewport не требует растягивать содержимое по высоте: без пустых распорок, крупных hero, `min-height`
  на рабочих строках и вертикального `space-between`. Header, tabs и toolbar компактны; высота следует содержимому.
  На широком экране название/план строки слева, связанные inputs/actions справа; tags и детали — вторым коротким
  уровнем. На узком экране группы складываются вниз без горизонтального scroll. Плотность достигается расположением
  и отступами, не мелким текстом; для touch сохраняются удобные цели. Обычный рабочий список должен помещаться
  в обозримую область; длинный список использует один общий scroll, без обрезания содержимого.
- Изоляция — в `browser:impl` (`DesktopDocument*`): свой origin `https://<id>.document.heartbeat.invalid/`, контекст в
  памяти, CSP без соединений, запросы кроме самого документа отклоняются, WebRTC — UDP только через прокси (его
  нет), конструкторы WebRTC удалены из окна страницы, TCP (TURN) идёт только в прокси контекста на закрытый порт,
  включая loopback (рантайм hb3: JCEF передаёт словарные настройки). Вызовы страницы —
  свой лимит (1) и лог trace; только Desktop. Проверка с настоящим Chromium —
  `./gradlew :features:browser:impl:browserDocumentNativeTest` (локально, нужен рабочий стол).

## Проверка

```bash
./gradlew :features:service-as-ui:api:jvmTest :features:service-as-ui:impl:jvmTest
./gradlew :features:service-as-ui:impl:detekt :features:service-as-ui:impl:detektMainJvm
./gradlew :platform-main:di-bundle:jvmTest
```
