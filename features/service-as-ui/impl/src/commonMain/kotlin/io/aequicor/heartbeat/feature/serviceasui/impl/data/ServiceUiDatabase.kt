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
import androidx.room.Update
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import kotlinx.coroutines.flow.Flow

/**
 * One card shown in a response; [chat] is the JSON of its chat, either [spec] (inline) or [surface] (a library surface
 * id) is set, [args] is a JSON object. Library rows created by older versions remain aliases to the newest library
 * row of their chat. Its [createdAt] advances on show so eviction always removes aliases before the current slot.
 * Specs and arguments are user data that never reach logs; only the profile limit evicts rows.
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
    @Query(
        "SELECT * FROM ui_cards WHERE (id = :id AND surface IS NULL) OR " +
            "(surface IS NOT NULL AND chat = (SELECT chat FROM ui_cards WHERE id = :id AND surface IS NOT NULL)) " +
            "ORDER BY createdAt DESC, rowid DESC LIMIT 1",
    )
    abstract fun observe(id: String): Flow<CardEntity?>

    @Query(
        "SELECT * FROM (SELECT * FROM ui_cards WHERE chat = :chat AND surface IS NOT NULL " +
            "ORDER BY createdAt DESC, rowid DESC LIMIT 1) WHERE surface = :surface",
    )
    abstract suspend fun latest(chat: String, surface: String): CardEntity?

    @Query(
        "SELECT * FROM ui_cards WHERE chat = :chat AND surface IS NOT NULL " +
            "ORDER BY createdAt DESC, rowid DESC LIMIT 1",
    )
    protected abstract suspend fun live(chat: String): CardEntity?

    @Query("SELECT COUNT(*) FROM ui_cards WHERE chat = :chat")
    protected abstract suspend fun count(chat: String): Int

    @Insert
    protected abstract suspend fun insert(row: CardEntity)

    @Update
    protected abstract suspend fun update(row: CardEntity)

    @Query(
        "DELETE FROM ui_cards WHERE id NOT IN " +
            "(SELECT id FROM ui_cards ORDER BY createdAt DESC, rowid DESC LIMIT :max)",
    )
    protected abstract suspend fun keepNewest(max: Int): Int

    /**
     * Reuses the current library row of the chat, otherwise inserts within [perChat], then evicts beyond
     * [perProfile]. Lookup and mutation are one transaction, so concurrent shows cannot create separate live slots.
     * Existing aliases stay older than the slot, including when the clock moves backwards.
     */
    @Transaction
    open suspend fun add(row: CardEntity, perChat: Int, perProfile: Int): CardStored? {
        val previous = if (row.surface != null) live(row.chat) else null
        return if (previous != null) {
            val replacement = row.copy(
                id = previous.id,
                callId = previous.callId,
                createdAt = maxOf(row.createdAt, previous.createdAt),
            )
            update(replacement)
            CardStored(previous.id, keepNewest(perProfile))
        } else {
            insertNew(row, perChat, perProfile)
        }
    }

    private suspend fun insertNew(row: CardEntity, perChat: Int, perProfile: Int): CardStored? {
        if (count(row.chat) >= perChat) return null
        insert(row)
        return CardStored(row.id, keepNewest(perProfile))
    }
}

/** Identity actually stored by one transaction, and its retention outcome. */
internal data class CardStored(val id: String, val evicted: Int)

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
