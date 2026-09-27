---
name: data-storage
description: "Хранение данных в Heartbeat — core:datastore (api/impl): DataStores с владельцем app/profile, key-value (KeyValueStore, StoreKey), своя Room-БД фичи (heartbeat.room, @Database + DatabaseSpec, DAO, миграции), время жизни записей (Retention: срок After/At/Daily, событие DataEvent, RecordRetention в строках), wipeProfile, репозитории фич и их логирование, тесты. Используй при добавлении таблиц, DAO, миграций, настроек, кэшей или репозиториев."
---

# Данные: `core:datastore`

Версии — `gradle/libs.versions.toml`.
Room KMP: https://developer.android.com/kotlin/multiplatform/room, DataStore KMP: https://developer.android.com/kotlin/multiplatform/datastore

## Модель

- **Владелец** — выбирается квалификатором инжекта:
  - `@ForScope(AppScope::class) DataStores` — данные приложения, живут всегда;
  - `@ForScope(ProfileScope::class) DataStores` — данные профиля: закрываются с профилем, при возврате в профиль
    те же; удаляются `StorageMaintenance.wipeProfile(id)`.
- **Удержание записи** — `Retention(expiry, event)`; удаляет **ядро**, фича только объявляет:
  - `Retention.Permanent` — живёт, пока живёт хранилище;
  - `Retention.expiring(Expiry.After(1.hours))`, `Expiry.At(instant)`, `Expiry.Daily(LocalTime(3, 0))` — по времени;
  - `Retention.untilEvent(SignedOut)` — по событию `DataEvent("auth.signed_out")`, которое фича вызывает через `stores.fire(SignedOut)`:
    - на app-владельце событие чистит записи всех владельцев;
    - на profile-владельце — только своего профиля;
  - `Retention(expiry, event)` — удаляется по тому условию, которое наступит первым.
- **Имена хранилищ** — `[a-z][a-z0-9_]*`, уникальны среди всех фич владельца: префикс фичи (`chat_settings`), `core_*` —
  ядро. Один spec на хранилище (top-level `val`): другой spec с тем же именем — `IllegalStateException`.
- **Имена ключей и событий** хранятся на диске — не переименовывать. После смены типа ключа старое значение читается как
  отсутствующее (`W`).

Фичи **не** создают `DataStore`/`Room.databaseBuilder` сами: это запрещает detekt (`LoggingInfrastructureBypass`).
Также фичи не пишут таймеры и чистки.

## Key-value

```kotlin
internal val SettingsSpec = KeyValueSpec("chat_settings", areValuesLogged = true)  // настройки без персональных данных
internal val DraftKey = jsonKey("draft", Draft.serializer())
internal val ModelKey = stringKey("model")

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ChatSettingsRepositoryImpl(
    @ForScope(ProfileScope::class) stores: DataStores,   // ← в ProfileScope-графе; для app-данных — AppScope
) : ChatSettingsRepository {
    private val store = stores.keyValue(SettingsSpec)     // один экземпляр на имя, повторный вызов вернёт тот же

    override fun model(): Flow<String?> = store.observe(ModelKey)
    override suspend fun setModel(id: String) = store.set(ModelKey, id)
    override suspend fun saveDraft(draft: Draft) = store.set(DraftKey, draft, Retention.expiring(Expiry.After(1.days)))
}
```

- **Типы ключей**: `stringKey`, `intKey`, `longKey`, `booleanKey`, `doubleKey`, `floatKey`, `stringSetKey`, `jsonKey(name, serializer)`.
- **Чтение** не возвращает истёкшую запись. JSON, который не удалось декодировать, читается как отсутствующее значение (`W`);
  диагностическая ошибка не содержит исходный JSON или сообщение пользовательского сериализатора.
- **Закрытие владельца** отменяет текущие операции и наблюдения KV. Сохранённая ссылка после закрытия больше не работает
  (`IllegalStateException`, в том числе при новой подписке на ранее созданный `Flow`). При возврате в профиль получи хранилище заново.
- **Логи** (`DS`) пишет ядро: ключ и удержание — `D`; значения — только с `areValuesLogged = true` (`I`, `old -> new`).
  Для персональных данных оставляй `false`.
- Секреты — не в `KeyValueStore` (см. `SecretStore` в скилле `ai-koog`).

## Своя БД фичи (Room)

`features/<name>/impl/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.heartbeat.room)   // androidx.room + KSP для всех таргетов, schemas/, :core:datastore:api
}
```

```kotlin
@Database(entities = [MessageEntity::class], version = 1)          // exportSchema = true → <module>/schemas (коммитить)
@ConstructedBy(ChatDatabaseConstructor::class)
internal abstract class ChatDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
}

@Suppress("KotlinNoActualForExpect") // Room generates the actual implementations
internal expect object ChatDatabaseConstructor : RoomDatabaseConstructor<ChatDatabase> {
    override fun initialize(): ChatDatabase
}

internal val ChatDatabaseSpec = DatabaseSpec("chat", ChatDatabaseConstructor::initialize, ChatMigrations.all)

// строки с временем жизни: колонки удержания встраиваются БЕЗ префикса, индекс — по сроку
@Entity(tableName = "messages", indices = [Index(RecordRetention.EXPIRES_AT_COLUMN)])
internal data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val text: String,
    @Embedded val retention: RecordRetention,
)

```

- **Настраивает ядро**: `BundledSQLiteDriver`, IO-диспетчер, логи открытия и миграций (`DB`), закрытие вместе с владельцем.
- **Удержание строк**: таблицы со всеми колонками `hb_*` ядро находит само. Истёкшие строки и строки событий оно удаляет:
  - при открытии БД, до первого запроса;
  - по таймеру, пока БД открыта;
  - при `fire`.

  `Flow` из DAO видят удаление.
- **Запись строки**: `RecordRetentions` (инжект) — `retentions.stamp(Retention.untilEvent(SignedOut))`.
  Для постоянных строк — `stamp(Retention.Permanent)`.
- **Таблицы без `RecordRetention`** ядро не трогает.
- Добавление `RecordRetention` в существующую сущность — изменение схемы: нужна миграция.
- Чтение БД строки по сроку **не** фильтрует: таймер может отставать, если устройство спало. Где истёкшая строка на
  экране недопустима, добавь в запрос `AND (hb_expires_at IS NULL OR hb_expires_at > :now)`.

### Миграции
- Любое изменение сущности → `version + 1` + миграция (`Migration(n, n+1)` в `DatabaseSpec.migrations` или `@AutoMigration`),
  схема в `schemas/` коммитится.
- Тест миграции на JVM (`MigrationTestHelper` с `BundledSQLiteDriver`).
- Никаких `fallbackToDestructiveMigration`.

### DAO
`suspend` для записи, `Flow` для наблюдения. Транзакции — `@Transaction` или `useWriterConnection { it.immediateTransaction { … } }`.

## Репозиторий фичи (`impl/data`)

```kotlin
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ChatRepositoryImpl(
    @ForScope(ProfileScope::class) stores: DataStores,
    private val retentions: RecordRetentions,
) : ChatRepository {
    private val log = Log.tag("ChatRepository")
    private val dao = stores.database(ChatDatabaseSpec).messages()   // один экземпляр БД на владельца и имя

    override fun observe(chatId: String): Flow<List<Message>> =
        dao.observeMessages(chatId).map { rows -> rows.map(MessageEntity::toDomain) }

    override suspend fun append(message: Message) {
        log.d { "append chatId=${message.chatId} id=${message.id}" }
        dao.insert(message.toEntity(retentions.stamp(Retention.expiring(Expiry.After(30.days)))))
    }
}
```

- Маппинг Entity ↔ domain в `impl/data`; Entity не утекают в стор/UI.
- DAO-операции логирует репозиторий (`d`). Открытие, миграции и чистки логирует ядро.

## Удаление данных профиля

`StorageMaintenance.wipeProfile(id)` (app-скоуп) — например, при удалении аккаунта с устройства. Для активного профиля —
`IllegalStateException`: сначала `profileSessions.close()`. Пустой ID отклоняется до доступа к каталогу хранения
(`IllegalArgumentException`); пути существующих профилей не меняются.

## Тесты
- **Репозиторий фичи**:
  - in-memory Room — `Room.inMemoryDatabaseBuilder<ChatDatabase>().setDriver(BundledSQLiteDriver()).build()` в `jvmTest`,
    `RecordRetention(createdAt, expiresAt, event)` — вручную;
  - KV — fake `KeyValueStore` на `MutableStateFlow`.
- **Ядро** (`core:datastore:impl`) тестируется во временной директории на виртуальном времени (`StorageTestEnv`).
  Тестовая Room-БД живёт в `jvmTest`: KSP `kspJvmTest`, без `@ConstructedBy`.

## Защищённые данные

API-ключи, токены и персональные поля хранить через профильный `SecretStore` из `core:secrets:api`
([контракт](../../../core/secrets/api/README.md)).
`StorageMaintenance.wipeProfile` вызывает app-scoped `ProfileStorageCleaner` contributions до удаления обычных файлов;
ошибка участника прерывает wipe. Секреты переживают переключение профиля, удаляются только явным wipe.
