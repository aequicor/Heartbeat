package io.aequicor.heartbeat.ds.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFollowStateTest {
    @Test
    fun `tokens follow the bottom until the user starts reading history`() {
        val following = ChatFollowState()
        assertTrue(following.shouldScrollOnUpdate(isUserScrolling = false))

        val readingHistory = following.onUserScroll(isAtLatest = false)
        repeat(20) {
            assertFalse(readingHistory.shouldScrollOnUpdate(isUserScrolling = false))
        }
    }

    @Test
    fun `an update cannot steal an active scroll gesture`() {
        assertFalse(ChatFollowState().shouldScrollOnUpdate(isUserScrolling = true))
    }

    @Test
    fun `returning to the bottom resumes following without a new conversation`() {
        val readingHistory = ChatFollowState().onUserScroll(isAtLatest = false)
        val atBottom = readingHistory.onUserScroll(isAtLatest = true)
        assertTrue(atBottom.shouldScrollOnUpdate(isUserScrolling = false))
    }

    @Test
    fun `jump action resumes following after scrolling through old messages`() {
        val state = ChatFollowState().onUserScroll(isAtLatest = false).onJumpToLatest()
        assertTrue(state.shouldScrollOnUpdate(isUserScrolling = false))
    }

    @Test
    fun `partially scrolled long message is still reading history`() {
        assertFalse(isChatAtLatest(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 32))
        assertFalse(isChatAtLatest(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = 0))
        assertTrue(isChatAtLatest(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `restored history position does not jump to the latest on first content`() {
        val restored = ChatFollowState(isFollowingLatest = isChatAtLatest(8, 12))
        assertFalse(restored.shouldScrollOnUpdate(isUserScrolling = false))
    }
}
