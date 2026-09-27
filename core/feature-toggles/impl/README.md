# core:feature-toggles:impl

Реализация [core:feature-toggles:api](../api/README.md). **Подключается только в `:platform-main:di-bundle`.**

| Класс | Роль |
|---|---|
| `FeatureToggleRegistryBindings` | `@Multibinds(allowEmpty = true) Set<FeatureToggle<*>>`: фичи добавляют тоглы через `@Provides @IntoSet` в `AppScope` |
| `ToggleRegistry` | реестр: сортировка по владельцу и ключу, конфликт объявлений с одним ключом — `IllegalStateException`, предупреждение о незарегистрированном тогле (один раз на ключ) |
| `ToggleOverrides` | локальные переопределения в app-KV `core_feature_toggles` (`@ForScope(AppScope::class) DataStores`); лог действующих переопределений при первом чтении; значение вне `Choice.options` — как отсутствующее |
| `DataStoreFeatureToggles` | `FeatureToggles`: переопределение, иначе default; при сбое хранилища — default с `W`, без исключения; наблюдение повторяет подписку через секунду до восстановления или отмены |
| `FeatureToggleControlImpl` | `FeatureToggleControl`: состояния всех тоглов, `setOverride` / `reset` (только зарегистрированные, только допустимые `options`), `resetAll` (очищает всё KV, включая ключи удалённых тоглов); изменения под `Mutex`, ошибки хранилища пробрасываются |

## Логи (`FT`)

| Уровень | Что |
|---|---|
| `I` | реестр при создании (ключи); действующие переопределения при первом чтении; каждое изменение `key: old (source) -> new (source)`; итог `reset all` |
| `D` | вычисленное значение `key = value (source)`, `get`, `reset` без переопределения |
| `W` | тогл читается без регистрации или с другим объявлением; сохранённое значение не входит в `options` (один раз на ключ); сбой хранилища при чтении (значение — default) |

Хранилище (`DS`) пишет только имена ключей: значения логирует `FT`.

## Тесты

- `./gradlew :core:feature-toggles:api:jvmTest` — `FeatureToggleTest`: ключи, владелец, проверки `Choice`.
- `./gradlew :core:feature-toggles:impl:jvmTest` — `FeatureTogglesTest` на in-memory `DataStores`: default,
  переопределение и сброс, `resetAll`, порядок и источники состояний, лог при старте, запреты, удалённая опция,
  незарегистрированный тогл, конфликт ключей, формат логов, восстановление подписки после сбоев и отмена повторов.
- Граф — `FeatureTogglesIntegrationTest` в [platform-main/di-bundle](../../../platform-main/di-bundle/README.md).
