# core:datastore:api

Контракт хранения данных: key-value и собственные Room-БД фич, владелец (app / profile), время жизни отдельных записей.
Инструкция для фич — скилл `data-storage`. Реализация —
[core:datastore:impl](../impl/README.md) (подключается только в `di-bundle`).

| Тип | Назначение |
|---|---|
| `DataStores` | хранилища одного владельца: `keyValue(spec)`, `database(spec)`, `fire(event)`; инжект с `@ForScope(AppScope::class)` или `@ForScope(ProfileScope::class)` |
| `StorageOwner` | `App` / `Profile(id)` — каталог и срок жизни хранилищ |
| `KeyValueSpec`, `KeyValueStore`, `StoreKey` | KV-хранилище: `observe` / `get` / `set(key, value, retention)` / `remove` / `clear`; ключи `stringKey` … `jsonKey` |
| `DatabaseSpec<T>` | Room-БД фичи: имя, `<Db>Constructor::initialize`, миграции |
| `Retention`, `Expiry`, `DataEvent` | время жизни записи: `Permanent`, `After(Duration)` / `At(Instant)` / `Daily(LocalTime)`, событие |
| `RecordRetention`, `RecordRetentions` | колонки удержания строки (`@Embedded` без префикса) и их вычисление при записи |
| `StorageMaintenance` | `wipeProfile(id)` — удалить данные неактивного профиля |

## Владельцы и жизненные циклы

| Нужно | Как |
|---|---|
| живёт всегда (app) | `@ForScope(AppScope::class) DataStores` + `Retention.Permanent` |
| пока выбран профиль (profile) | `@ForScope(ProfileScope::class) DataStores` — при смене профиля закрыто, при возврате те же данные |
| удаляется через время / в момент / ежедневно (time) | `Retention.expiring(Expiry.After(1.hours) \| At(instant) \| Daily(LocalTime(3, 0)))` |
| удаляется по событию (action) | `Retention.untilEvent(event)` + `stores.fire(event)` |

Удаляет ядро: при открытии хранилища, по таймеру, пока оно открыто, и при `fire`. Чтение KV никогда не возвращает истёкшую
запись. `fire` у app-владельца чистит записи события у всех владельцев, у profile-владельца — только у своего профиля.

Закрытие владельца отменяет текущие операции и наблюдения KV; старые ссылки и новые подписки на ранее созданные `Flow`
после закрытия получают `IllegalStateException`. При повторном открытии профиля нужно получить новый `KeyValueStore`.
Пустой ID профиля отклоняется при обращении к его каталогу хранения, включая `wipeProfile`.

## Экспорт

`api(...)`: `room-runtime` (`RoomDatabase`, `Migration`, `@ColumnInfo`), `kotlinx-datetime` (`LocalTime`),
`kotlinx-serialization-core` (`KSerializer`), `core:profile-facade:api` (`ProfileId`), `kotlinx-coroutines-core` (`Flow`).
Фичам с БД нужен ещё плагин `heartbeat.room` (KSP + схемы).
