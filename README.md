This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM).

Пастельная дизайн-система Aequicor Glass UI (Glassmorphism) и отдельное приложение **UIKit Sandbox**:
[запуск, платформы и проверка](platform-main/uikit-sandbox/README.md).

```powershell
.\gradlew.bat :platform-main:uikit-sandbox:desktop:run
```

Точки входа приложения Heartbeat — в [platform-main](./platform-main); раскладка остальных модулей и правила — [CLAUDE.md](CLAUDE.md).

* [platform-main/shared](./platform-main/shared/src) — общий вход: создание root-компонента и его Compose-рендер;
  для iOS собирается статический framework `Shared`.
* [platform-main/android](./platform-main/android) — Android-приложение.
* [platform-main/desktop](./platform-main/desktop) — Desktop-приложение (Windows, macOS).
* [platform-main/ios](./platform-main/ios) — Xcode-проект iOS; Swift-код только хостит Compose из framework `Shared`.

### Запуск

- Android: `./gradlew :platform-main:android:assembleDebug`
- Desktop:
  - Hot reload: `./gradlew :platform-main:desktop:hotRun --auto`
  - Обычный запуск: `./gradlew :platform-main:desktop:run`
- iOS (только macOS): откройте [platform-main/ios](./platform-main/ios) в Xcode и запустите оттуда.

### Тесты

- Все KMP-тесты: `./gradlew allTests`
- Быстрые JVM-тесты: `./gradlew jvmTest`
- Metro-граф и интеграция скоупов: `./gradlew :platform-main:di-bundle:jvmTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
