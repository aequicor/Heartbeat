---
name: data-storage
description: "Хранение данных в Heartbeat — Room KMP (core:database: сущности, DAO, миграции, BundledSQLiteDriver, фабрики per-platform, KSP для всех таргетов) и DataStore KMP (core:datastore: Preferences, пути per-platform, логирующая обёртка), репозитории фич и их логирование. Используй при добавлении таблиц, DAO, миграций, настроек или репозиториев."
---

# Данные: Room + DataStore

Версии — [tech-stack.md](../../../docs/ai/tech-stack.md). Документация: https://developer.android.com/kotlin/multiplatform/room , https://developer.android.com/kotlin/multiplatform/datastore

## Room (`core:database`)

Одна БД `HeartbeatDatabase` в `core:database`. Сущности и DAO фич объявляются в `core:database` в пакете `…core.database.<feature>` (Room требует, чтобы `@Database` видел все сущности), а репозитории — в `impl` фич.

```kotlin
@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = true)
@ConstructedBy(HeartbeatDatabaseConstructor::class)
abstract class HeartbeatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}

@Suppress("KotlinNoActualForExpect") // Room generates actual implementations
expect object HeartbeatDatabaseConstructor : RoomDatabaseConstructor<HeartbeatDatabase> {
    override fun initialize(): HeartbeatDatabase
}

fun RoomDatabase.Builder<HeartbeatDatabase>.configure(dispatchers: DispatcherProvider): HeartbeatDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(dispatchers.io)
        .addMigrations(*HeartbeatMigrations.all)
        .addCallback(LoggingRoomCallback)          // логирует onCreate/onOpen/миграции (тег DB)
        .build()
```

Фабрики builder'а per-platform (`expect/actual` или через DI с `PlatformContext`):
- Android: `Room.databaseBuilder<HeartbeatDatabase>(context, context.getDatabasePath("heartbeat.db").absolutePath)`
- JVM: `Room.databaseBuilder<HeartbeatDatabase>(File(appDataDir, "heartbeat.db").absolutePath)` — `appDataDir`: `%APPDATA%\Aequicor\Heartbeat` (Windows), `~/Library/Application Support/Heartbeat` (macOS)
- iOS: `Room.databaseBuilder<HeartbeatDatabase>(documentDirectory() + "/heartbeat.db")`

Gradle (в convention-плагине): плагины `androidx.room` + `com.google.devtools.ksp`, `room { schemaDirectory("$projectDir/schemas") }`,
KSP для каждого таргета: `kspAndroid`, `kspJvm`, `kspIosArm64`, `kspIosSimulatorArm64` → `libs.androidx.room.compiler`.

### Миграции
- Любое изменение сущности → `version + 1` + миграция (`Migration(n, n+1)` или `@AutoMigration`), схема в `schemas/` коммитится.
- Тест миграции на JVM (`MigrationTestHelper` с `BundledSQLiteDriver`).
- Никаких `fallbackToDestructiveMigration` в release.

### DAO
`suspend` для записи, `Flow` для наблюдения. Транзакции — `@Transaction` или `useWriterConnection { it.immediateTransaction { … } }`.

## DataStore (`core:datastore`)

```kotlin
fun createPreferencesDataStore(path: () -> String): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { path().toPath() })   // okio Path

// имена файлов: settings.preferences_pb, feature_toggles.preferences_pb
```

Пути: Android — `context.filesDir.resolve(name)`, JVM — `appDataDir/name`, iOS — `NSDocumentDirectory/name`.
**Один экземпляр на файл** (`@SingleIn(AppScope::class)`), иначе DataStore упадёт.

`LoggingDataStore` — обёртка из `core:datastore`: логирует `edit` (ключи и старое→новое для настроек — `I`, для прочего — `D`), ошибки чтения (`IOException` → `emptyPreferences()` + `W`). Фичи используют её, а не голый `DataStore`.

## Репозиторий фичи (`impl/data`)

```kotlin
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
internal class ChatRepositoryImpl(private val dao: ChatDao) : ChatRepository {
    private val log = Log.tag("ChatRepository")

    override fun observe(chatId: String): Flow<List<Message>> =
        dao.observeMessages(chatId).map { rows -> rows.map(MessageEntity::toDomain) }

    override suspend fun append(message: Message) {
        log.d { "append chatId=${message.chatId} id=${message.id}" }
        dao.insert(message.toEntity())
    }
}
```

- Маппинг Entity ↔ domain в `impl/data`; Entity не утекают в стор/UI.
- Секреты — не в Room/DataStore (см. `SecretStore` в скилле `ai-koog`).

## Тесты
In-memory: `Room.inMemoryDatabaseBuilder<HeartbeatDatabase>().setDriver(BundledSQLiteDriver()).build()` в `jvmTest`; DataStore — во временной директории (`Files.createTempDirectory`), закрывай scope после теста.
