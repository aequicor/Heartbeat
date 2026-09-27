---
name: feature-toggle
description: "Фича-тоглы Heartbeat — объявление тогла, чтение как Flow, регистрация через Metro-мультибиндинг, управление из внутренней панели (FeatureToggleControl), логирование изменений; а также создание/изменение самого модуля core:feature-toggles. Используй, когда новую функциональность нужно спрятать за флагом или поменять систему тоглов."
---

# Фича-тоглы

`core:feature-toggles:{api,impl}`. Локальные переопределения
в app-хранилище `core:datastore` + единая точка управления `FeatureToggleControl` (бэкенд панели). Удалённый источник —
отдельное будущее решение.

## API (`io.aequicor.heartbeat.core.featuretoggles`)

```kotlin
sealed interface FeatureToggle<T : Any> {
    val key: String            // "<owner>.<name>": [a-z][a-z0-9_]* через точку, ≤ 128; хранится на диске
    val default: T
    val description: String    // показывается в панели
    val owner: String          // = первый сегмент key

    data class Flag(key, description, default: Boolean = false) : FeatureToggle<Boolean>
    data class Choice(key, description, options: List<String>, default: String = первая опция) : FeatureToggle<String>
}

interface FeatureToggles {                       // код фич: только чтение
    fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T>
    suspend fun <T : Any> get(toggle: FeatureToggle<T>): T
}

interface FeatureToggleControl {                 // панель тоглов / отладочные инструменты
    val registered: List<FeatureToggle<*>>       // по owner, затем key
    fun observeStates(): Flow<List<ToggleState<*>>>
    suspend fun <T : Any> setOverride(toggle: FeatureToggle<T>, value: T)
    suspend fun reset(toggle: FeatureToggle<*>)
    suspend fun resetAll()
}

data class ToggleState<T : Any>(val toggle: FeatureToggle<T>, val value: T, val source: ToggleSource)
enum class ToggleSource { LocalOverride, Default }   // первый присутствующий выигрывает
```

`api` зависит только от coroutines — его можно подключать в `api`-модуль фичи (`implementation(projects.core.featureToggles.api)`,
`api(...)`, если объявления тоглов входят в контракт фичи).

## Как добавить тогл в фичу

```kotlin
// features/chat/impl/.../ChatToggles.kt (или api, если тогл читают другие фичи)
object ChatToggles {
    val StreamingResponses = FeatureToggle.Flag(
        key = "chat.streaming_responses",
        description = "Потоковый вывод ответа модели",
    )
}

// features/chat/impl/.../di/ChatTogglesContribution.kt — регистрация для панели
@ContributesTo(AppScope::class)
interface ChatTogglesContribution {
    @Provides @IntoSet
    fun streaming(): FeatureToggle<*> = ChatToggles.StreamingResponses
}
```

Тип результата — ровно `FeatureToggle<*>`: с `FeatureToggle.Flag` или `FeatureToggle<Boolean>` Metro положит тогл в другой
мультибиндинг и регистрация тихо потеряется (заметно только по `W` при чтении). Несколько тоглов сразу —
`@Provides @ElementsIntoSet fun all(): Set<FeatureToggle<*>> = setOf(…)`.

Чтение — инжект `FeatureToggles` в стор / эффекты / репозиторий и `observe(...)`. В Compose — только через состояние
стора, не читай тоглы в composable напрямую. Менять значения из кода фич нельзя: `FeatureToggleControl` — только
для `features/toggles-panel` и `platform-main` (хук `check-conventions.sh` отклоняет импорт в других местах).

## Поведение реализации

- Хранение: `@ForScope(AppScope::class) DataStores` → KV `core_feature_toggles`, запись на тогл (имя = ключ).
  Переопределения общие для всех профилей и переживают перезапуск.
- Значение = переопределение, иначе `default`. Переопределение, равное `default`, остаётся переопределением.
- `setOverride` / `reset` — только зарегистрированный тогл (и значение из `Choice.options`), иначе `IllegalArgumentException`.
  Изменения сериализованы; `resetAll` чистит всё KV, включая ключи удалённых тоглов.
- Сохранённая опция, которой больше нет в `options`, читается как отсутствующая (`W` один раз на ключ).
- Сбой хранилища: `FeatureToggles` отдаёт default (`W`) и не бросает; `FeatureToggleControl` пробрасывает ошибку панели.
- Незарегистрированный тогл читается, но логирует `W` один раз: он не виден в панели.
- Два разных объявления с одним ключом → `IllegalStateException` при создании реестра.
- Логи `FT`: изменение `I` `key: old (source) -> new (source)`, переопределения при первом чтении `I`,
  реестр `I`, значения `D`. Руками изменения тоглов не логируй.

## Панель управления

Фича `features/toggles-panel` поверх `FeatureToggleControl`: список `observeStates()`, группировка по `owner`,
переключатели / выбор, сброс одного и всех, поиск, отметка переопределённых. Появится после `design-system`
(экраны — только на токенах и `Hb*`). Доступ в release — отдельным решением.

## Тесты

- Код фичи с тоглом: фейк `FeatureToggles` (например, `MutableStateFlow` на тогл) — без `core:feature-toggles:impl`.
- Сам модуль: `./gradlew :core:feature-toggles:api:jvmTest :core:feature-toggles:impl:jvmTest`,
  граф — `:platform-main:di-bundle:jvmTest` (`FeatureTogglesIntegrationTest`).

## Правила
- Новая незавершённая функциональность — `Flag` с `default = false`.
- Удаление тогла после раскатки — отдельная задача: удалить объявление, ветки кода и регистрацию (миграция не нужна —
  ключ игнорируется, `resetAll` удаляет и его).
- Имена ключей не переиспользуются и не переименовываются.
- Точные аннотации мультибиндинга Metro — см. скилл `di-metro`.
