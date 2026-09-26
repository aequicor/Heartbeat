---
name: feature-toggle
description: "Фича-тоглы Heartbeat — объявление тогла, чтение как Flow, регистрация через Metro-мультибиндинг, управление из внутренней панели, логирование изменений; а также создание/изменение самого модуля core:feature-toggles. Используй, когда новую функциональность нужно спрятать за флагом или поменять систему тоглов."
---

# Фича-тоглы

Тонкая обёртка над DataStore (`core:datastore`) + единая панель управления внутри приложения (пока только локальные значения; удалённый источник — будущий ADR).

## API `core:feature-toggles`

```kotlin
package io.aequicor.heartbeat.core.toggles

/** Объявление тогла. Живёт в модуле фичи (api — если читают другие фичи, иначе impl). */
sealed interface FeatureToggle<T : Any> {
    val key: String            // "<feature>.<name>", snake_case, уникален
    val default: T
    val description: String   // показывается в панели
    val owner: String          // фича-владелец

    data class Flag(
        override val key: String,
        override val default: Boolean,
        override val description: String,
        override val owner: String,
    ) : FeatureToggle<Boolean>

    data class Choice(
        override val key: String,
        override val default: String,
        val options: List<String>,
        override val description: String,
        override val owner: String,
    ) : FeatureToggle<String>
}

interface FeatureToggles {
    fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T>
    suspend fun <T : Any> get(toggle: FeatureToggle<T>): T
    suspend fun <T : Any> set(toggle: FeatureToggle<T>, value: T, source: ToggleSource = ToggleSource.DebugPanel)
    suspend fun reset(toggle: FeatureToggle<*>)
    val registered: Set<FeatureToggle<*>>   // из Metro-мультибиндинга
}

enum class ToggleSource { Default, DebugPanel, Migration }
```

Реализация (`internal class DataStoreFeatureToggles`, `@SingleIn(AppScope::class)`, `@ContributesBinding(AppScope::class)`):
- хранит значения в отдельном `DataStore<Preferences>` (`feature_toggles.preferences_pb`);
- `observe` = `data.map { it[key] ?: default }.distinctUntilChanged()`;
- `set` логирует `FT: <key>: <old> -> <new> (source)` на уровне INFO — **обязательно** (изменение конфигурации);
- при старте логирует список переопределённых (не default) тоглов.

## Как добавить тогл в фичу

```kotlin
// features/chat/impl/.../ChatToggles.kt
object ChatToggles {
    val StreamingResponses = FeatureToggle.Flag(
        key = "chat.streaming_responses",
        default = false,
        description = "Потоковый вывод ответа модели",
        owner = "chat",
    )
}

// регистрация для панели
@ContributesTo(AppScope::class)
interface ChatTogglesContribution {
    @Provides @IntoSet
    fun streaming(): FeatureToggle<*> = ChatToggles.StreamingResponses
}
```

Использование в сторе/эффектах — через инжект `FeatureToggles` и `observe(...)`. В Compose — только через состояние стора, не читай тоглы в composable напрямую.

## Панель управления

Фича `features/toggles-panel` (debug-меню): список `registered`, группировка по `owner`, переключатели/выбор, сброс, поиск. Доступна в debug-сборках (и в release — по скрытому жесту, если так решит ADR).

## Правила
- Новая незавершённая функциональность — `default = false`.
- Удаление тогла после раскатки — отдельная задача: удалить объявление, ветки кода и ключ (миграция DataStore не нужна — ключ просто игнорируется).
- Имена ключей не переиспользуются.
- Точные аннотации мультибиндинга Metro — см. скилл `di-metro`.
