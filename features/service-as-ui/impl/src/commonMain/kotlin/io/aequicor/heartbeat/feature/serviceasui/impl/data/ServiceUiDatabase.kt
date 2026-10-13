package io.aequicor.heartbeat.feature.serviceasui.impl.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import kotlinx.coroutines.flow.Flow

/**
 * One card shown in a response; [chat] is the JSON of its chat, either [spec] (inline) or [surface] (a library surface
 * id) is set, [args] is a JSON object. Specs and arguments are user data that never reach logs. Rows are permanent:
 * only the profile limit evicts the oldest.
 */
@Entity(tableName = "ui_cards", indices = [Index("chat"), Index("createdAt")])
internal data class CardEntity(
    @PrimaryKey val id: String,
    val chat: String,
    val callId: String?,
    val title: String,
    val spec: String?,
    val surface: String?,
    val args: String,
    val createdAt: Long,
) {
    override fun toString(): String = "CardEntity(id=$id)"
}

/** Cards; a chat holds a bounded number and the profile keeps the newest. */
@Dao
internal abstract class CardDao {
    @Query("SELECT * FROM ui_cards WHERE id = :id")
    abstract fun observe(id: String): Flow<CardEntity?>

    @Query("SELECT COUNT(*) FROM ui_cards WHERE chat = :chat")
    protected abstract suspend fun count(chat: String): Int

    @Insert
    protected abstract suspend fun insert(row: CardEntity)

    @Query(
        "DELETE FROM ui_cards WHERE id NOT IN " +
            "(SELECT id FROM ui_cards ORDER BY createdAt DESC, rowid DESC LIMIT :max)",
    )
    protected abstract suspend fun keepNewest(max: Int): Int

    /**
     * Inserts [row] unless its chat already holds [perChat] cards, then drops the oldest rows beyond [perProfile], in
     * one transaction. Returns how many rows were dropped, or null when the chat was full.
     */
    @Transaction
    open suspend fun add(row: CardEntity, perChat: Int, perProfile: Int): Int? {
        if (count(row.chat) >= perChat) return null
        insert(row)
        return keepNewest(perProfile)
    }
}

/** The feature's profile database: cards shown in responses. */
@Database(entities = [CardEntity::class], version = 1)
@ConstructedBy(ServiceUiDatabaseConstructor::class)
internal abstract class ServiceUiDatabase : RoomDatabase() {
    abstract fun cards(): CardDao
}

@Suppress("KotlinNoActualForExpect") // Room generates platform constructors.
internal expect object ServiceUiDatabaseConstructor : RoomDatabaseConstructor<ServiceUiDatabase> {
    override fun initialize(): ServiceUiDatabase
}

internal val ServiceUiDatabaseSpec = DatabaseSpec("service_ui", ServiceUiDatabaseConstructor::initialize)
