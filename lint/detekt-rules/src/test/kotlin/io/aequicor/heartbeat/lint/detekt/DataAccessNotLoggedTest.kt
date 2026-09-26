package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DataAccessNotLoggedTest {

    private val rule = DataAccessNotLogged(Config.empty)

    @Test
    fun `reports repository operations without logs`() {
        val code = """
            class ChatRepositoryImpl(private val dao: ChatDao) : ChatRepository {
                override suspend fun delete(chatId: ChatId) = dao.delete(chatId)
                suspend fun save(chat: Chat) { dao.insert(chat) }
                private fun map(e: Entity) = e.toChat()
            }
            class SettingsDataSource(private val store: DataStore) {
                suspend fun setTheme(theme: Theme) { store.edit { it[THEME] = theme.name } }
            }
        """.trimIndent()

        assertEquals(3, rule.lint(code).size)
    }

    @Test
    fun `accepts logged operations, interfaces and other classes`() {
        val code = """
            interface ChatRepository { suspend fun delete(chatId: ChatId) }
            class ChatRepositoryImpl(private val dao: ChatDao) : ChatRepository {
                override suspend fun delete(chatId: ChatId) {
                    log.d { "delete chatId=" + chatId }
                    dao.delete(chatId)
                }
            }
            class ChatMapper { fun map(e: Entity) = e.toChat() }
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
