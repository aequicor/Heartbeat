package io.aequicor.heartbeat.feature.serviceasui.impl.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceUiLiveCardDatabaseTest {
    private val directory = Files.createTempDirectory("service-ui-live-cards")
    private val path = directory.resolve("cards.db")
    private var database: ServiceUiDatabase? = null

    @AfterTest
    fun close() {
        database?.close()
        check(directory.toFile().deleteRecursively())
    }

    @Test
    fun `repeated shows replace source title and args in one durable slot across restart`() = runTest {
        val dao = open()
        val original = row("original", at = 1)
        assertEquals(CardStored("original", 0), dao.add(original, 2, 10))
        val replacement = row("ignored-new-id", at = 2).copy(
            surface = "harness:other:renamed",
            title = "Updated app",
            args = "{\"selected\":2}",
        )
        assertEquals(CardStored("original", 0), dao.add(replacement, 2, 10))
        val expected = replacement.copy(id = original.id, callId = original.callId)
        assertEquals(expected, open().observe("original").first())
        assertNull(open().observe("ignored-new-id").first())
        assertNull(open().latest("chat", original.surface.orEmpty()))
        assertEquals(expected, open().latest("chat", replacement.surface.orEmpty()))
    }

    @Test
    fun `concurrent first shows converge on one stored identity`() = runTest {
        val dao = open()
        val stored = List(8) { index -> async { dao.add(row("candidate-$index", at = index.toLong()), 2, 10) } }
            .awaitAll()
        assertEquals(1, stored.map { it?.id }.distinct().size)
        assertEquals(0, stored.sumOf { it?.evicted ?: 0 })
        assertEquals(CardStored("inline", 0), dao.add(row("inline").copy(spec = "{}", surface = null), 2, 10))
        assertNull(dao.add(row("overflow").copy(spec = "{}", surface = null), 2, 10))
        assertEquals(stored.first()?.id, dao.add(row("still-updates"), 2, 10)?.id)
    }

    @Test
    fun `legacy duplicates resolve their newest state before a new show and never cross chats`() = runTest {
        open().observe("initialize").first()
        seedLegacy(row("old", at = 1), row("current", at = 2), row("other", "another", at = 3))
        val dao = open()
        assertEquals("current", dao.observe("old").first()?.id)
        assertEquals("other", dao.observe("other").first()?.id)
        val replacement = row("not-inserted", at = 0).copy(surface = "harness:new:app", args = "{\"view\":4}")
        assertEquals(CardStored("current", 0), dao.add(replacement, 1, 10))
        val current = open().observe("old").first()
        assertEquals("current", current?.id)
        assertEquals(replacement.args, current?.args)
        assertEquals(2L, current?.createdAt)
        assertNull(open().latest("chat", "harness:owner:app"))
        assertEquals(current, open().latest("chat", "harness:new:app"))
    }

    @Test
    fun `profile eviction drops legacy aliases before the live slot and never revives stale data`() = runTest {
        open().observe("initialize").first()
        seedLegacy(row("old", at = 1), row("current", at = 2))
        val dao = open()
        assertEquals(CardStored("current", 0), dao.add(row("update", at = 4), 1, 2))
        assertEquals(CardStored("other", 1), dao.add(row("other", "another", at = 5), 1, 2))
        assertNull(dao.observe("old").first())
        assertEquals("current", dao.latest("chat", "harness:owner:app")?.id)
        dao.add(row("newest", "third", at = 6), 1, 2)
        assertNull(dao.latest("chat", "harness:owner:app"))
        assertNull(dao.observe("current").first())
    }

    private fun open(): CardDao {
        database?.close()
        return Room.databaseBuilder<ServiceUiDatabase>(path.toString()).setDriver(BundledSQLiteDriver()).build()
            .also { database = it }.cards()
    }

    private fun seedLegacy(vararg rows: CardEntity) {
        database?.close()
        database = null
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            rows.forEach { row ->
                connection.prepare("INSERT INTO ui_cards VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
                    statement.bindText(1, row.id)
                    statement.bindText(2, row.chat)
                    statement.bindText(3, row.callId.orEmpty())
                    statement.bindText(4, row.title)
                    statement.bindNull(5)
                    statement.bindText(6, row.surface.orEmpty())
                    statement.bindText(7, row.args)
                    statement.bindLong(8, row.createdAt)
                    statement.step()
                }
            }
        }
    }

    private fun row(id: String, chat: String = "chat", at: Long = 0) = CardEntity(
        id,
        chat,
        "call-$id",
        "App $id",
        null,
        "harness:owner:app",
        "{}",
        at,
    )
}
