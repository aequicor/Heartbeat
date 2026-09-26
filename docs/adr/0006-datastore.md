# ADR-0006: Хранение данных — core:datastore:api / impl, владельцы и удержание записей

- Статус: принято
- Дата: 2026-09-26
- Затрагивает: `core:datastore:api`, `core:datastore:impl`, `core:common` (`Clock`), `platform-main:di-bundle`,
  `build-logic` (`heartbeat.room`), `lint:detekt-rules` (`LoggingInfrastructureBypass`), фичи с данными

> 0004 зарезервирован за `core:state-machine`, 0005 — `core:network`.

## Контекст

- **У каждой фичи может быть своя БД.** Прежний план — одна `HeartbeatDatabase` в `core:database` с сущностями всех
  фич — заставлял фичи класть свои таблицы в `core` и связывал их миграции.
- **У данных разный жизненный цикл:**
  - `app` — живут всегда;
  - `profile` — принадлежат профилю, при смене профиля недоступны, при возврате снова те же;
  - `time` — удаляются через интервал, в заданный момент или ежедневно в ЧЧ:ММ;
  - `action` — удаляются по событию (например, выход из аккаунта).
- **Время жизни задаётся на уровне отдельных записей** (ключ KV, строка БД). Реализует его ядро: фичи только объявляют
  время жизни и не пишут таймеры и чистки.
- **Политика логирования**: чтение и запись DataStore, открытие и миграции БД, чистки логируются централизованно.
- **ADR-0002**: постоянный `ActiveProfileStorage` должен жить в `core:datastore`.
- **Разделение api / impl** — как у `core:network`: от impl зависит только `di-bundle`.

## Решение

### Две оси: владелец × удержание

- **Владелец** — свойство хранилища. Он выбирается квалификатором инжекта:
  - `@ForScope(AppScope::class) DataStores` — живёт всегда;
  - `@ForScope(ProfileScope::class) DataStores` — закрывается с профилем и лежит в каталоге профиля.
- **Удержание** — свойство записи:
  - `Retention(expiry: Expiry?, event: DataEvent?)`;
  - `Expiry.After(Duration)` / `At(Instant)` / `Daily(LocalTime)`;
  - запись удаляется при первом наступившем условии.

Четыре исходных цикла выражаются так: app = `App` + `Permanent`, profile = `Profile` + `Permanent`,
time = `Expiring`, action = `UntilEvent`. Кроме того, их можно сочетать, например «кэш профиля на 1 час».

### api (`io.aequicor.heartbeat.core.datastore`)

- **`DataStores`**:
  - `keyValue(KeyValueSpec)` и `database(DatabaseSpec<T>)` — один экземпляр на имя;
  - `fire(DataEvent)`: app-владелец чистит записи события у всех владельцев, profile-владелец — только у своего профиля.
- **`KeyValueStore`** — типизированные `StoreKey<T>`: примитивы, `Set<String>`, JSON через `KSerializer`.
  - `set(key, value, retention)`;
  - чтение никогда не возвращает истёкшую запись.
- **`DatabaseSpec(name, factory = <Db>Constructor::initialize, migrations)`** — БД открывает ядро.
- **`RecordRetention`** — три колонки `hb_created_at`, `hb_expires_at`, `hb_event`, `@Embedded` в сущность фичи
  **без префикса**. `RecordRetentions.stamp(retention)` вычисляет их в момент записи.
- **`StorageMaintenance.wipeProfile(id)`** — удалить данные профиля; для активного профиля — отказ.
- **Имена хранилищ** (`[a-z][a-z0-9_]*`), **ключей и событий** валидируются: они становятся путями и хранятся на диске.
  Имя хранилища уникально среди всех фич владельца (префикс фичи; `core_*` — ядро). Другой spec с тем же именем даёт
  `IllegalStateException` — иначе, например, `areValuesLogged` одной фичи раскрыл бы в логах значения другой.
- api экспортирует `room-runtime`, `kotlinx-datetime`, `kotlinx-serialization-core`: они входят в контракт
  (`RoomDatabase`, `LocalTime`, `KSerializer`).

### impl

- **Файлы** лежат в `<StorageRoot>/storage/{app | profiles/<hex(profileId)>}/{kv/<name>.preferences_pb, db/<name>.db, events.json}`.
  `StorageRoot` привязывается per-platform:
  - Android — `filesDir`;
  - JVM — `%APPDATA%\Aequicor\Heartbeat`, `~/Library/Application Support/Heartbeat`, `$XDG_DATA_HOME/heartbeat`;
  - iOS — `Application Support`.

  Тестовый граф подменяет его с `priority = 1`.
- **`StoreRegistry`** (`@SingleIn(AppScope)`) — единственное место, где создаются DataStore и Room.
  - Один `DataStore` на файл на весь процесс (DataStore падает на втором активном экземпляре). У каждого своя `Job`
    под app-скоупом: профиль можно закрыть и открыть заново, а `wipeProfile` отменяет и дожидается `Job` перед удалением.
  - Room-БД закрываются вместе с владельцем.
- **KV**: удержание хранится рядом с записью (`__hb.exp.<key>`, `__hb.evt.<key>`, `__hb.at.<key>`).
  - Перед первой операцией из хранилища удаляются истёкшие записи и записи уже случившихся событий.
  - Повреждённый файл заменяется пустыми данными с `E`-логом, ошибка чтения — с `W`.
- **Room** (`DatabaseRetention` — также `RoomDatabase.Callback`):
  - в `onOpen` (до первого запроса фичи) находятся таблицы со всеми колонками `hb_*` и удаляются истёкшие строки
    и строки уже случившихся событий;
  - дальше чистит таймер и `fire` — через `useWriterConnection` + `immediateTransaction`, затем
    `invalidationTracker.refresh`, чтобы `Flow` из DAO увидели удаление;
  - индексы ядро не создаёт, потому что Room сверяет их при валидации схемы. Фича объявляет `@Index("hb_expires_at")` сама.
- **Таймер** (`runRetentionTimer`) — корутина в скоупе владельца.
  - Спит до ближайшего дедлайна, но не дольше 15 минут: скачки wall-clock догоняются.
  - Перезапускается при каждом изменении хранилища: `data` у DataStore, `InvalidationTracker` у Room.
  - Чистка выполняется downstream от сна (`transformLatest` → `collect`), поэтому изменение, которое делает она сама,
    не отменяет её на полпути.
- **События**: журнал `event → firedAt` в `events.json` владельца, атомарная запись (tmp + move).
  - Открытые хранилища чистятся сразу.
  - Закрытые — при открытии: удаляются записи с `createdAt <= firedAt`. Так событие настигает и БД, которую ядро само
    открыть не может (фабрика есть только у фичи).
  - Профильные хранилища читают журнал приложения и журнал своего профиля.
- **Room без reified-типа**: `Room.databaseBuilder` reified и различается по платформам (Android — `Context`).
  - Поэтому `RoomBuilderFactory` привязан per-platform, как engine в ADR-0005.
  - Билдер типизирован `RoomDatabase`, реальный класс создаёт `spec.factory`, результат приводится к `T`.
  - `SQLiteDriver` оборачивается `DirectoryCreatingDriver`: SQLite не создаёт каталог.
- **Логи**:
  - `DS`: `D` — имя ключа и удержание, `I` — открытие, чистки и события; значения — только при `KeyValueSpec.areValuesLogged`;
  - `DB`: `I` — открытие (версия, таблицы удержания, число удалённых строк), миграции, чистки;
  - содержимое записей не логируется никогда.
- **`DataStoreActiveProfileStorage`** — `@ContributesBinding(AppScope, priority = 0)`, KV `core_profile` / `active_profile_id`.
- **`wipeProfile`** резервирует id: пока идёт удаление, профиль нельзя открыть (`attach` падает). Каталог сравнивается
  целиком (`<hex>/`), чтобы id-префикс (`alice` / `alice2`) не задел другой профиль.
- **Устойчивость**:
  - упавший таймер логирует `W` и перезапускается через минуту;
  - повреждённый журнал событий не перезаписывается молча: он сохраняется как `events.json.corrupt` (`E`);
  - значение, записанное под другим типом ключа, читается как отсутствующее (`W`).
- **Android-граф** получает `Context`: `createHeartbeatGraph(context)` → `@DependencyGraph.Factory`,
  хранится `applicationContext`.
- **`core:common`** отдаёт `kotlin.time.Clock` (`ClockBindings`) — время удержания управляется в тестах.

### Сборка

- `heartbeat.room` — модуль со своей БД (impl фичи):
  - плагин `androidx.room` (схемы в `<module>/schemas`, коммитятся);
  - KSP `room-compiler` для `android` / `jvm` / `iosArm64` / `iosSimulatorArm64`;
  - `implementation(:core:datastore:api)`.
- `LoggingInfrastructureBypass`: `databaseBuilder` / `inMemoryDatabaseBuilder` разрешены только в `core.datastore`.
- detekt исключает `build/` и для задач с type resolution, куда попадает KSP-вывод (`*_Impl`).
- Новые библиотеки: `kotlinx-datetime` 0.8.0 (`Expiry.Daily` в системном поясе) и `okio` 3.18.2
  (файлы журнала, удаление каталогов; уже транзитивно из DataStore).

## Альтернативы

| Вариант | Плюсы | Минусы | Почему нет |
|---|---|---|---|
| Одна `HeartbeatDatabase` в `core:database` | одна схема, одна миграция, JOIN между фичами | сущности фич в `core`, общие миграции, `core` знает о фичах | противоречит «у фичи своя БД» и границам модулей |
| Удержание на уровне хранилища целиком (TTL файла / БД) | просто: удалить файл | нельзя держать в одной таблице постоянные и временные записи | требование — отдельные записи |
| Удержание реализует фича (свои колонки, свои таймеры) | гибко | дублирование в каждой фиче, ошибки в чистке, нет единых логов | требование — фича только задаёт время жизни |
| Отдельная служебная таблица `__hb_retention(table, rowid, …)` вместо `@Embedded` | сущности фич не меняются | Room её не знает (нет `Flow`-инвалидации), `rowid` ломается при `VACUUM`/`WITHOUT ROWID`, двойная запись | колонки в самой строке проще и атомарны с записью |
| 4 плоских типа жизненного цикла | ровно как в формулировке | нельзя «кэш профиля на час», time/action только глобальные | ортогональные оси покрывают все сочетания |
| Профильные данные стирать при смене профиля | нет накопления данных | переключение профиля теряет данные | выбрано «хранятся на профиль», удаление — `wipeProfile` |
| `expect/actual` для путей и Room-билдера | меньше DI | detekt с type resolution ломается на `expect/actual` (ADR-0005), Android нужен `Context` | per-platform `@ContributesBinding` |
| Журнал событий в DataStore | единый механизм | `onOpen` Room синхронный — нужен синхронный доступ к журналу | маленький JSON-файл через okio |

## Последствия

- **Фича со своими данными**:
  - в `impl` применяет `heartbeat.room` (если нужна БД);
  - объявляет `@Database` с `@ConstructedBy` и `DatabaseSpec`;
  - получает БД через `@ForScope(<Owner>) DataStores.database(spec)` — вызов идемпотентен, обычно прямо в репозитории;
  - сущности с удержанием встраивают `RecordRetention`, записи ставятся через `RecordRetentions.stamp`.
- **Ограничения**:
  - события сравниваются по wall-clock в миллисекундах: запись, сделанная в ту же миллисекунду, что и `fire`, удаляется;
    при переводе часов назад более новые записи могут пережить событие;
  - таймер работает, только пока хранилище открыто; закрытые хранилища чистятся при открытии, а чтение KV и так
    фильтрует истёкшие записи;
  - строки БД при чтении **не** фильтруются. Между дедлайном и срабатыванием таймера (устройство спало, `delay` стоял)
    истёкшая строка видна через DAO. Где это критично, фича добавляет в запрос `(hb_expires_at IS NULL OR hb_expires_at > :now)`;
  - `RecordRetentions.stamp` берёт время до `insert`. Строка, вставленная параллельно с `fire` с меткой раньше события,
    может пережить его до следующего открытия БД. В KV этой гонки нет: время берётся внутри `edit`;
  - индекс по `hb_expires_at` — ответственность фичи.
- **Тесты**:
  - `:core:datastore:impl:jvmTest` — KV на виртуальном времени, Room-БД через KSP в `jvmTest`;
  - `DataStoreIntegrationTest` в `di-bundle`.
- **Обновлены**:
  - `docs/ai/{architecture,logging-policy,tech-stack}.md`;
  - скиллы `data-storage`, `module-setup`, `di-metro`;
  - правила `.claude/rules/{feature-api,feature-impl,tests}.md`;
  - хук `check-conventions.sh` (импорт любого `core.*.impl` вне своего модуля);
  - `CLAUDE.md`, README модулей.
