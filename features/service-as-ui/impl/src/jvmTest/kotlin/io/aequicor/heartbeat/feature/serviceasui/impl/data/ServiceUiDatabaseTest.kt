package io.aequicor.heartbeat.feature.serviceasui.impl.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardLimits
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ServiceUiDatabaseTest {
    private val database = Room.inMemoryDatabaseBuilder<ServiceUiDatabase>()
        .setDriver(BundledSQLiteDriver())
        .build()
    private val cards = RoomUiCards(TestStores(database))

    @AfterTest
    fun close() {
        database.close()
    }

    @Test
    fun `cards round-trip with their source, chat and arguments`() = runTest {
        val inline = card("c_inline", One, UiCardSource.Inline("""{"version":1,"body":{"type":"divider"}}"""))
        val surface = card("c_surface", Two, UiCardSource.Surface(UiSurfaceId("harness:h_1:map")))

        assertEquals(UiCardAdded.Stored(evicted = 0), cards.add(inline))
        assertEquals(UiCardAdded.Stored(evicted = 0), cards.add(surface))
        assertEquals(inline, cards.observe("c_inline").first())
        assertEquals(surface, cards.observe("c_surface").first())
        assertNull(cards.observe("c_unknown").first())
    }

    @Test
    fun `a full chat refuses a card and the profile keeps its newest cards`() = runTest {
        repeat(UiCardLimits.PER_CHAT) { cards.add(card("c_$it", One, at = it.toLong())) }
        assertEquals(UiCardAdded.ChatFull, cards.add(card("c_more", One, at = 100)))
        assertEquals(UiCardAdded.Stored(evicted = 0), cards.add(card("c_other", Two, at = 101)))

        val dao = database.cards()
        val row = { id: String, at: Long -> CardEntity(id, "chat-$id", null, "T", "{}", null, "{}", at) }
        assertEquals(0, dao.add(row("c_new", 200), perChat = 1, perProfile = 70))
        assertEquals(1, dao.add(row("c_newer", 201), perChat = 1, perProfile = 66))
        assertNull(cards.observe("c_0").first())
        assertEquals("c_1", cards.observe("c_1").first()?.id)
    }

    @Test
    fun `an unreadable row reads as a missing card`() = runTest {
        database.cards().add(CardEntity("c_broken", "not json", null, "T", "{}", null, "{}", 0), 1, 10)
        assertNull(cards.observe("c_broken").first())
    }

    @Test
    fun `latest card belongs to the exact chat and surface with insertion order for equal timestamps`() = runTest {
        val surface = UiSurfaceId("harness:h_1:workout")
        val source = UiCardSource.Surface(surface)
        cards.add(card("old", One, source, at = 1))
        cards.add(card("recent", One, source, at = 2))
        cards.add(card("same-time", One, source, at = 2))
        cards.add(card("other-chat", Two, source, at = 3))
        cards.add(card("other-owner", One, UiCardSource.Surface(UiSurfaceId("harness:h_2:workout")), at = 4))
        cards.add(card("anonymous", One, at = 5))
        assertEquals("same-time", cards.latest(One, surface)?.id)
        assertEquals("other-chat", cards.latest(Two, surface)?.id)
        assertNull(cards.latest(One, UiSurfaceId("harness:h_1:missing")))
    }

    private fun card(
        id: String,
        chat: SessionRef,
        source: UiCardSource = UiCardSource.Inline("""{"version":1,"body":{"type":"divider"}}"""),
        at: Long = 0,
    ) = UiCard(
        id = id,
        chat = chat,
        callId = "call-$id",
        title = "Card $id",
        source = source,
        args = JsonObject(mapOf("pr" to JsonPrimitive(7))),
        createdAt = Instant.fromEpochMilliseconds(at),
    )

    private class TestStores(private val database: ServiceUiDatabase) : DataStores {
        override val owner: StorageOwner = StorageOwner.App

        override fun filesDirectory(name: String): String = error("unused")

        override fun keyValue(spec: KeyValueSpec): KeyValueStore = error("unused")

        @Suppress("UNCHECKED_CAST") // The repository opens only this database.
        override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = database as T

        override suspend fun fire(event: DataEvent) = Unit
    }

    private companion object {
        val One = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
        val Two = SessionRef(EngineId("pi"), SessionSourceId("s"), "two")
    }
}
