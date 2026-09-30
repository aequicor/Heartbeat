<p align="center">
  <img src="assets/branding/heartbeat-banner.png" alt="Heartbeat — ИИ-студия Aequicor" width="100%" />
</p>

<h1 align="center">Heartbeat</h1>

<p align="center">
  <strong>ИИ-студия для ваших моделей, проектов и разговоров.</strong><br />
  Kotlin Multiplatform · Compose Multiplatform · Aequicor
</p>

<p align="center">
  <a href="#возможности">Возможности</a> ·
  <a href="#быстрый-старт">Быстрый старт</a> ·
  <a href="#сборка-и-установка">Установка</a> ·
  <a href="#разработка">Разработка</a>
</p>

---

Heartbeat объединяет ИИ-движки, подключения, модели и историю бесед в одном рабочем пространстве.
Общий код приложения работает на **Android, Windows, macOS и iOS**, а интерфейс учитывает привычки платформы:
Material на Android, Fluent на Windows и macOS UI на Mac.

Знак Heartbeat — светящаяся линия ЭКГ на сине-фиолетовой плитке. Один визуальный стиль объединяет
иконку приложения, Desktop-окно, Dock, установочные пакеты и этот README.

## Возможности

| Рабочее пространство | Что внутри |
| --- | --- |
| **ИИ-студия** | Чаты, потоковые ответы, выбор модели и уровня reasoning effort. |
| **Несколько движков** | Встроенный Pi на Desktop; адаптеры Koog, Codex и Claude Code. |
| **Ваши подключения** | OpenAI, Anthropic, совместимые серверы и локальный Ollama через Koog. |
| **Профили и история** | Подключения и беседы принадлежат профилю; секреты хранятся отдельно. |
| **Работа с проектами** | Desktop-инструменты для файлов и команд; уровни доверия и подтверждения действий. |
| **Единый интерфейс** | Общая дизайн-система, светлая и тёмная темы, компоненты для каждой платформы. |

> Приложение активно развивается. Часть сценариев доступна через фича-тоглы: например,
> `ai_studio.engine_runtime`, `ai.engine_connections`, `ai.koog`, `ai.codex` и `ai.claude`.
> Pi, Codex и Claude Code запускают локальные процессы на Desktop; Pi на Android/iOS пока представлен заглушкой.
> Актуальные контракты и ограничения описаны в KDoc соответствующих модулей.

## Быстрый старт

Клонируйте репозиторий:

```bash
git clone https://github.com/aequicor/Heartbeat.git
cd Heartbeat
```

Для сборки нужен **JDK 21**; Gradle настраивает Java toolchain проекта. Для Android установите SDK 37
и укажите его путь в `local.properties` (`sdk.dir=...`) или в `ANDROID_HOME`.
Для iOS нужен macOS с Xcode; минимальная версия iOS в проекте — **18.2**.

Запуск Desktop на macOS:

```bash
./gradlew :platform-main:desktop:run
```

Запуск Desktop на Windows:

```powershell
.\gradlew.bat :platform-main:desktop:run
```

Для разработки с Hot Reload:

```bash
./gradlew :platform-main:desktop:hotRun --auto
```

На Windows используйте `.\gradlew.bat` вместо `./gradlew` в остальных Gradle-командах.

## Сборка и установка

Установочные файлы собираются из исходников. Desktop-пакеты включают Java runtime,
поэтому пользователю установленного приложения отдельный JDK не нужен.
MSI собирается на Windows, DMG — на macOS.

| Платформа | Собрать | Установить |
| --- | --- | --- |
| **Android** | `./gradlew :platform-main:android:assembleDebug` | Установить APK из `platform-main/android/build/outputs/apk/debug/`. |
| **Windows** | `.\gradlew.bat :platform-main:desktop:packageMsi` | Запустить MSI из `platform-main/desktop/build/compose/binaries/main/msi/`. |
| **macOS** | `./gradlew :platform-main:desktop:packageDmg` | Открыть DMG из `platform-main/desktop/build/compose/binaries/main/dmg/` и перенести приложение в Applications. |
| **iOS** | Открыть `platform-main/ios/iosApp.xcodeproj` в Xcode | Выбрать команду подписи и iPhone либо Simulator, затем выполнить Run. |

Для Windows-сборки MSI требуется WiX Toolset, поддерживаемый используемым JDK/jpackage.
Для установки iOS на устройство настройте signing в Xcode и `TEAM_ID` в
[`Config.xcconfig`](platform-main/ios/Configuration/Config.xcconfig).

Android можно установить на подключённое устройство:

```bash
./gradlew :platform-main:android:installDebug
```

Иконки подготовлены для каждой платформы: адаптивная и тематическая Android-иконка,
обычный/тёмный/тонированный варианты iOS, ICO для Windows и ICNS для macOS.
Исходные изображения и промпты — в [`assets/branding`](assets/branding/README.md).

## Разработка

Приложение построено на Kotlin Multiplatform и Compose Multiplatform. Decompose отвечает за навигацию,
FlowMVI — за состояние экранов, Metro — за зависимости, а бизнес-сценарии описаны машинами состояний.

| Каталог | Назначение |
| --- | --- |
| [`platform-main`](platform-main) | Android/Desktop/iOS, общий вход и сборка DI-графа. |
| [`features`](features) | Пользовательские сценарии; контракты в `api`, реализация и UI в `impl`. |
| [`core`](core) | Навигация, хранилища, сеть, секреты, логирование и инфраструктура. |
| [`design-system`](design-system) | Токены, темы, компоненты, адаптивные макеты и каталог. |
| [`build-logic`](build-logic) | Общие Gradle-плагины и правила сборки. |

Проверки:

```bash
./gradlew jvmTest
./gradlew :platform-main:di-bundle:jvmTest
./gradlew detekt
```

Все KMP-тесты, включая доступные платформенные таргеты:

```bash
./gradlew allTests
```

Дизайн-систему можно посмотреть отдельно в [UIKit Sandbox](platform-main/uikit-sandbox/README.md):

```bash
./gradlew :platform-main:uikit-sandbox:desktop:run
```

Правила проекта и архитектуры — в [`CLAUDE.md`](CLAUDE.md), инструкции для агентов — в [`AGENTS.md`](AGENTS.md).
