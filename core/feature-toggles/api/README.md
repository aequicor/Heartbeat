# core:feature-toggles:api

Контракт фича-тоглов: объявление, чтение и единая точка управления локальными переопределениями.
Решение — [ADR-0007](../../../docs/adr/0007-feature-toggles.md), инструкция для фич — скилл `feature-toggle`.
Реализация — [core:feature-toggles:impl](../impl/README.md) (подключается только в `di-bundle`).

Зависит только от `kotlinx-coroutines-core` (`api`): модуль можно подключать в `api`-модули фич.

| Тип | Назначение |
|---|---|
| `FeatureToggle<T>` | объявление: `Flag(key, description, default = false)`, `Choice(key, description, options, default = options.first())`; ключ `<owner>.<name>`, `owner` — первый сегмент |
| `FeatureToggles` | чтение для кода фич: `observe(toggle): Flow<T>`, `get(toggle)` |
| `FeatureToggleControl` | управление (бэкенд панели): `registered`, `observeStates()`, `setOverride`, `reset`, `resetAll` |
| `ToggleState<T>` | тогл + значение + `ToggleSource`, `isOverridden` |
| `ToggleSource` | откуда значение: `LocalOverride` → `Default` (первый присутствующий источник выигрывает) |

```kotlin
object ChatToggles {
    val StreamingResponses = FeatureToggle.Flag("chat.streaming_responses", "Потоковый вывод ответа модели")
}

@ContributesTo(AppScope::class)
interface ChatTogglesContribution {
    @Provides @IntoSet
    fun streaming(): FeatureToggle<*> = ChatToggles.StreamingResponses
}

@Inject class ChatEffectsImpl(toggles: FeatureToggles) : ChatEffects {
    val streaming: Flow<Boolean> = toggles.observe(ChatToggles.StreamingResponses)
}
```
