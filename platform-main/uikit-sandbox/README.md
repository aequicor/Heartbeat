# UIKit Sandbox

Отдельное приложение для разработки пастельной дизайн-системы Aequicor Glass UI. Идентификаторы, launcher
и упаковка независимы от основного приложения Heartbeat.

Интерфейс уплотнён по уточнённому запросу пользователя: спокойная навигация без
маркетингового hero-блока, небольшие элементы и сдержанный Glass-фон оставляют
больше места основному содержимому. Нейтральный ответ ассистента отображается
на цельной белой поверхности в светлой теме и спокойной непрозрачной поверхности
в тёмной. Навигация, история с панелью действий и ввод разделены тонкими границами.
Граф модулей и набор возможностей сохраняются.

Типографика: display 22 sp, title 17 sp, body 14 sp, label 13 sp, caption 11 sp.
Шкала отступов — 2/4/6/8/12/16/20 dp, скруглений — 6/10/14 dp. Тема JVM задаёт
компактную высоту контролов 32 dp; Android/iOS сохраняют области нажатия 48 dp.
Эта плотность определяется платформой независимо от выбранного нативного кита.

## Запуск

Windows / macOS Desktop:

Требуется JDK 21 (macOS-кит содержит Java 21 bytecode); Gradle toolchain выбирает его
по каталогу версий. Общие KMP-модули и Android сохраняют JVM 17.

```powershell
.\gradlew.bat :platform-main:uikit-sandbox:desktop:run
```

На macOS используйте `./gradlew` вместо `.\gradlew.bat`.
Сборка установщика: `:platform-main:uikit-sandbox:desktop:packageDistributionForCurrentOS`.

Android:

```powershell
.\gradlew.bat :platform-main:uikit-sandbox:android:assembleDebug
.\gradlew.bat :platform-main:uikit-sandbox:android:installDebug
```

Приложение устанавливается как `io.aequicor.heartbeat.uikit.sandbox`.

iOS (только macOS + Xcode): откройте `ios/UIKitSandbox.xcodeproj`, выберите
`SandboxApp` и симулятор. Для устройства задайте свою команду подписи в
`ios/Configuration/Config.xcconfig`. Build phase собирает Kotlin framework
`SandboxKit` из `:platform-main:uikit-sandbox:shared` (имя отличается от модуля приложения `UIKitSandbox`, иначе Swift игнорирует импорт).

## Что можно проверить

- Foundation: пастельная палитра, типографика, шкала отступов и стеклянные поверхности.
- Components: кнопки, поля ввода, карточки, состояния и badges.
- Layouts: независимые Row / Column / FlowRow / lazy list и адаптивные панели.
- Chat playground: потоковый текст и Markdown — заголовки, списки, таблицы, цитаты и code fences;
  изменение цвета, ширины и выравнивания ответа. Длинный ответ разбивается на элементы общего lazy list.
- Результаты инструментов раскрываются по нажатию: Markdown, буквальный вывод консоли и diff
  с обозначениями добавленных/удалённых строк. Длинные payload разбиты на фрагменты и
  виртуализированы общим списком чата, без вложенной вертикальной прокрутки.
- Composer: многострочный редактор, меню «+» для заметки, примеров Markdown/tool result и длинной сессии;
  переключатели режима Ask/Plan и профиля ответа. Меню поддерживают клавиатуру, Escape и возврат фокуса.
- Timeline: закреплённые заголовки сессий, Load earlier / загрузка старой истории с сохранением позиции,
  демонстрация 10 000 исторических сообщений и текущего ответа. Обновление потока сохраняет предыдущие
  сообщения и обрабатывает только изменённое сообщение.
- Отправка текста запускает локальную генерацию фрагментами. Stop отменяет её,
  Start fresh / «Начать заново» возвращает примеры. Ответ не зависит от содержания запроса; сеть не используется.
- Светлая / тёмная / системная тема, RU / EN, Glass UI / нативные контролы.
- Blur перекрывающих панелей через Haze, прозрачная заливка и мягкие тени;
  верхний и нижний края transcript затухают градиентом без блокирования ссылок и кнопок.
- В нативном режиме — выбор доступного платформенного кита.
- При чтении истории новые фрагменты не должны возвращать пользователя вниз;
  кнопка перехода к последнему сообщению возобновляет слежение.

Настройки и история демонстрации хранятся в памяти. Смена языка обновляет интерфейс,
а существующие сообщения сохраняются; «Начать заново» создаёт примеры на выбранном языке.

## Проверка

```powershell
.\gradlew.bat :design-system:tokens:jvmTest :design-system:resources:jvmTest :design-system:adaptive:jvmTest :design-system:components:jvmTest :design-system:catalog:jvmTest :platform-main:uikit-sandbox:desktop:test
.\gradlew.bat :platform-main:uikit-sandbox:desktop:createDistributable :platform-main:uikit-sandbox:android:assembleDebug
.\gradlew.bat detekt
```

Набор тестов включает локализацию, сохранение ввода, меню composer, раскрытие tool calls,
закреплённые заголовки, сохранение позиции при загрузке истории, поток и рендер платформенных
китов. Отдельные тесты ограничивают число скомпонованных строк для 10 000 сообщений и длинного
ответа. Изображения фактического Compose-рендера сохраняются в
`design-system/catalog/build/previews/`.

Для компактного Chat и Glass UI на Windows 26.09.2026 подтверждены
**81 JVM-тест**, Windows `createDistributable`,
Android `assembleDebug`, общий Detekt и анализ с типами для затронутых DS-модулей
и Desktop. Через computer-use проверены меню/hover/Escape,
Markdown, раскрытие инструментов, sticky headers, длинная история, ввод и поток.
Результаты и ограничения — [проверка Chat и Glass UI](../../docs/ai/chat-evolution-qa.md).
После уплотнения повторно проверены hover/клик заголовка инструмента,
меню composer и Escape с восстановлением фокуса; актуальные Compose-превью
подтверждают доступность ввода и действий на экране 390×480.
Проверки предыдущего этапа Soft UI, включая запуск Windows EXE через computer-use,
сохранены в [предыдущем отчёте динамического UI QA](../../docs/ai/ui-dynamic-qa.md).

Последний проход 27.09.2026: **151 тест** (components — 131, catalog — 20),
общий Detekt, Android APK и Windows distribution. Проверены также предыдущие
наборы tokens/layouts/resources/adaptive; подробная история проверок — в QA-отчёте.
Computer-use подтвердил hover, раскрытие инструментов, разделители, прокрутку
и обе темы; Compose-тесты проверяют в том числе окно 390×480.

Ограничения проверки: iOS/Xcode и упаковка macOS требуют macOS. Отдельные
`adaptive:detektMainJvm` и `components:detektMainJvm` завершаются успешно, но сообщают
диагностические ошибки анализатора для совместно загруженных `expect`/`actual`;
их анализ с типами неполон.
Это ограничение следует учитывать отдельно от компиляции и обычного Detekt.

Архитектура: [ADR-0008](../../docs/adr/0008-design-system-sandbox.md).
Markdown и инструменты: [ADR-0010](../../docs/adr/0010-markdown-tool-results.md).
Текущий визуальный стиль: [ADR-0011](../../docs/adr/0011-glass-surfaces.md).
История пастельной палитры: [ADR-0009](../../docs/adr/0009-pastel-neumorphism.md).
