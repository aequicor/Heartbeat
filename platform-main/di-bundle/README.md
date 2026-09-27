# platform-main:di-bundle

Единственное место, где собирается Metro-граф приложения. Единственный модуль, которому разрешено зависеть от `…:impl`
(фич и `core`). Точки входа `android`, `desktop` и `shared` (iOS) зависят от `di-bundle`, а не от `impl`.

## Почему граф на каждую платформу

Metro собирает контрибуции там, где компилируется `@DependencyGraph`. Если контрибуции есть в `androidMain`/`jvmMain`/`iosMain`
(например, `PlatformInfo` из `core:common`), граф обязан быть объявлен в платформенном source set'е:

```
commonMain/HeartbeatGraph.kt          interface HeartbeatGraph { dispatchers, platformInfo, profileSessions }
androidMain/AndroidHeartbeatGraph.kt  @DependencyGraph(AppScope::class) + Factory(@Provides Context) + createHeartbeatGraph(context)
jvmMain/JvmHeartbeatGraph.kt          то же
iosMain/IosHeartbeatGraph.kt          то же (оба iOS-таргета)
```

Точка входа платформы:

```kotlin
Log.init(isDebug)
val graph = createHeartbeatGraph()            // Android: createHeartbeatGraph(application) — хранится applicationContext
graph.profileSessions.restore()
```

`ProfileGraph` и графы фич (`@GraphExtension`) генерируются здесь же, вместе с графом приложения. Поэтому модуль должен
видеть все `impl`.

## Добавить модуль в граф

1. `implementation(projects.features.<x>.impl)` (или `core:<x>:impl`) в `commonMain.dependencies`.
2. `api(...)` — только для того, что нужно точкам входа (контракты, которые фигурируют в `HeartbeatGraph`).
3. `./gradlew :platform-main:di-bundle:compileKotlinJvm`: все ошибки Metro (`MissingBinding`, циклы) видны без сборки приложения.

## Тесты

`./gradlew :platform-main:di-bundle:jvmTest`. Тестовый граф `TestAppGraph` собирает реальные контрибуции всех модулей
плюс тестовые: фичу с `@GraphExtension`, shared-фабрики и приостанавливающееся «дисковое» хранилище профиля.
Хранилища `core:datastore` пишут во временный каталог «устройства» (`PersistedProfile.storageRoot`, `TestStorageRoot`).

| Тест | Что проверяет |
|---|---|
| `JvmHeartbeatGraphTest` | граф видит common- и jvm-контрибуции соседних модулей, `internal`-реализации |
| закрытие профиля | каскадно закрывает скоупы фич, отменяет их корутины, app-скоуп жив |
| смена профиля | старая сессия закрыта; повторный `open` того же id ничего не делает |
| восстановление | профиль поднимается в «новом процессе» и не поднимается после выхода |
| retained-граф | тот же граф после поворота; после смерти процесса — новый граф с состоянием, изменённым **после** поворота; закрыт при уничтожении |
| shared-объект | живёт, пока его держит хоть один компонент; повторный `close` аренды безопасен; упавшая фабрика не оставляет скоуп |
| выход и смена профиля из корутины профиля | запись в хранилище выполняется до конца, промежуточного `null` нет |
| `NetworkIntegrationTest` | один `HttpClient` на процесс (engine платформы из `jvmMain`), закрывается вместе с app-скоупом |
| `FeatureTogglesIntegrationTest` | тогл, внесённый `@IntoSet`, виден в `FeatureToggleControl.registered`; переопределение хранится в app-KV `core_feature_toggles` и читается через `FeatureToggles`; переживает смену профиля |
| `DataStoreIntegrationTest` | `@ForScope(AppScope)` / `@ForScope(ProfileScope)` `DataStores` — разные владельцы; хранилища профиля закрываются при смене и возвращаются с теми же данными; app-событие чистит записи всех владельцев; `wipeProfile` только для неактивного профиля; закрытие app-скоупа закрывает хранилища |

iOS-тесты (`iosSimulatorArm64Test`) требуют установленного Xcode. Компиляция под iOS работает и без него.
