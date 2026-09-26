# core:common

Базовые платформенные примитивы, которые нужны всем слоям. Реализации отдаются через Metro (`AppScope`).

| Тип | Назначение |
|---|---|
| `DispatcherProvider` | `main` / `default` / `io`. Всегда инжектируется, `Dispatchers.*` напрямую в продовом коде не используется |
| `PlatformInfo`, `HostPlatform` | ОС, на которой запущено приложение (`Android`, `Ios`, `MacOs`, `Windows`, `Linux`); по ней точка входа выбирает UI-кит |

## Использование

```kotlin
@Inject
internal class ChatRepositoryImpl(
    private val dispatchers: DispatcherProvider,
) : ChatRepository {
    override suspend fun load(id: ChatId) = withContext(dispatchers.io) { /* … */ }
}
```

В тестах подставляй свой `DispatcherProvider` на `StandardTestDispatcher`.

## Платформы

- `Dispatchers.Main`: модуль сам подключает `kotlinx-coroutines-android` (Looper) и `kotlinx-coroutines-swing` (EDT Compose Desktop);
  на iOS main-очередь встроена.
- `PlatformInfo` контрибутится из платформенных source set'ов (`androidMain` / `jvmMain` / `iosMain`). Поэтому
  граф приложения объявляется per-platform, см. [platform-main/di-bundle](../../platform-main/di-bundle/README.md).
- На JVM ОС определяется по `os.name` (`JvmPlatformInfo.hostOf`).

## Тесты

`./gradlew :core:common:jvmTest`
