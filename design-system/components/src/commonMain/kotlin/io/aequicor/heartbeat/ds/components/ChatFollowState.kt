package io.aequicor.heartbeat.ds.components

/** Reading history remains uninterrupted until the user returns to the latest message. */
internal data class ChatFollowState(val isFollowingLatest: Boolean = true) {
    fun onUserScroll(isAtLatest: Boolean): ChatFollowState = copy(isFollowingLatest = isAtLatest)

    fun onJumpToLatest(): ChatFollowState = copy(isFollowingLatest = true)

    fun shouldScrollOnUpdate(isUserScrolling: Boolean): Boolean = isFollowingLatest && !isUserScrolling
}

internal fun isChatAtLatest(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
