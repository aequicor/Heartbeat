# core:datastore:impl

Реализация [core:datastore:api](../api/README.md). **Подключается только в `:platform-main:di-bundle`.**
Решение — [ADR-0006](../../../docs/adr/0006-datastore.md).

| Класс | Роль |
|---|---|
| `AppDataStores` / `ProfileDataStores` | `@ForScope(AppScope)` / `@ForScope(ProfileScope)` `DataStores`; делегируют в `OwnerStores`, закрываются со скоупом владельца |
| `StoreRegistry` | единственное место создания DataStore и Room: один `DataStore` на файл на процесс (своя `Job` под app-скоупом), открытие БД, `fire`, `wipeProfile` |
| `OwnerStores` | открытые KV и БД одного владельца; закрывает БД при закрытии скоупа, KV-операции и подписки привязаны к тому же скоупу |
| `LoggingKeyValueStore` | KV поверх `DataStore<Preferences>`: типы ключей, удержание рядом с записью (`__hb.exp/evt/at.<key>`), чистки, логи `DS` |
| `DatabaseRetention` | `RoomDatabase.Callback`: таблицы с колонками `hb_*`, чистка в `onOpen` и по таймеру/событию, логи `DB` |
| `runRetentionTimer` | сон до ближайшего дедлайна (≤ 15 мин), перезапуск на изменения хранилища; после сбоя — `W` и повтор через минуту |
| `EventJournal` | `events.json` владельца: когда было последнее срабатывание события (для закрытых хранилищ) |
| `RetentionClock`, `RecordRetentionsImpl` | дедлайны `After` / `At` / `Daily` по `Clock` и часовому поясу |
| `StorageLayout`, `StorageRoot` | пути; `StorageRoot` — per-platform (`filesDir` / app data / `Application Support`), тесты подменяют |
| `RoomBuilderFactory` | `Room.databaseBuilder` per-platform (Android — `Context`) |
| `DirectoryCreatingDriver`, `LoggingMigration` | создание каталога БД; лог ручных миграций |
| `StorageMaintenanceImpl` | `wipeProfile` с проверкой, что профиль не активен |
| `DataStoreActiveProfileStorage` | постоянный `ActiveProfileStorage` (`priority = 0`), app KV `core_profile` |

## Файлы

```
<StorageRoot>/storage/app/{kv/<name>.preferences_pb, db/<name>.db, events.json}
<StorageRoot>/storage/profiles/<hex(profileId)>/{kv/…, db/…, events.json}
```

## Логи

| Тег | Уровень | Что |
|---|---|---|
| `DS` | `D` | `get`/`set`/`remove` — имя ключа и удержание; открытие хранилища (сколько записей удалено) |
| `DS` | `I` | открытие файла, `clear`, чистка по сроку / событию (число записей), `fire`; значения — только при `KeyValueSpec.areValuesLogged` |
| `DS` | `W` / `E` | ошибка чтения (пустые данные), повреждённый файл (заменён пустым), нечитаемый / повреждённый журнал событий (сохранён как `.corrupt`), значение другого типа или невалидный JSON, сбой таймера |
| `DB` | `I` | открытие (версия, таблицы удержания, удалено строк), создание, миграция `n -> m`, чистки, закрытие |
| `DB` | `W` | destructive migration |

Содержимое записей и строк в логи не попадает.

## Тесты

`./gradlew :core:datastore:impl:jvmTest`:
- `KeyValueStoreTest` (`commonTest`, виртуальное время `StorageTestEnv`): типы, JSON, истечение `After` / `At` / `Daily`
  и таймер, события (свои / app → профили / закрытые хранилища), переживание смены профиля и «перезапуска процесса»,
  `wipeProfile`, повреждённый файл, значения в логах;
- `KeyValueLifecycleTest`, `KeyValuePrivacyTest`: закрытие сохранённых ссылок, отмена внешних подписок и операций,
  приватность ошибок стандартных и пользовательских JSON-сериализаторов;
- `StorageBoundaryTest`, `EventJournalTest`, `RetentionTimerTest`: безопасные пути, порядок меток событий,
  завершение потока дедлайнов;
- `DataStoreActiveProfileStorageTest`;
- `DatabaseRetentionTest` (`jvmTest`, Room через `kspJvmTest`): каталог владельца, таймер и `Flow` DAO, события сразу
  и при открытии, строки после события, таблицы `rooms` / `room_members`, БД без удержания, закрытие дочерних скоупов.

Граф — `DataStoreIntegrationTest` в [platform-main/di-bundle](../../../platform-main/di-bundle/README.md).
