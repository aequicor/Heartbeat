package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

/**
 * A stable preorder of logical helper chats, independent of native subagent trees. Missing parents and cyclic
 * ancestry become roots, so filtered, archived or malformed metadata never makes a conversation unreachable.
 * The caller supplies sibling order; descendants inherit their root's visible sidebar section.
 */
internal class SidebarSessionTree(sessions: List<SessionUi>) {
    private val indexed = sessions.distinctBy { it.id }.associateBy { it.id }
    private val parents = indexed.values.associate { it.id to parentOf(it) }
    private val children = indexed.values.groupBy { parents[it.id] }
    val roots: List<SessionUi> = children[null].orEmpty()

    fun all(): List<SessionUi> = rows(roots)

    fun rows(roots: List<SessionUi>): List<SessionUi> = buildList {
        val pending = ArrayDeque<Pair<SessionUi, Int>>()
        roots.asReversed().forEach { pending.addLast(it to 0) }
        while (pending.isNotEmpty()) {
            val (session, depth) = pending.removeLast()
            add(
                session.copy(
                    depth = depth,
                    isNestedInSidebar = session.isNestedInSidebar || parents[session.id] != null,
                ),
            )
            children[session.id].orEmpty().asReversed().forEach { pending.addLast(it to depth + 1) }
        }
    }

    private fun parentOf(session: SessionUi): String? {
        val parent = session.parentChatId?.takeIf { it in indexed } ?: return null
        val seen = mutableSetOf(session.id)
        var ancestor: String? = parent
        while (ancestor != null && seen.add(ancestor)) ancestor = indexed[ancestor]?.parentChatId
        return parent.takeIf { ancestor == null }
    }
}
