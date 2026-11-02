package io.aequicor.heartbeat.feature.aistudio.screenmodel

/**
 * Two-level grouping of full conversations, independent of native execution trees. Creator and visual root
 * remain distinct. Missing roots and invalid ancestry keep a conversation reachable as an ungrouped row.
 * Older DTOs use their historical parent links, flattened under the last known root. Visibility is a caller concern.
 */
class SidebarSessionTree(sessions: List<SessionUi>) {
    private val indexed = sessions.distinctBy { it.id }.associateBy { it.id }
    private val parents = indexed.values.associate { it.id to parentOf(it) }
    private val children = indexed.values.groupBy { parents[it.id] }
    val roots: List<SessionUi> = children[null].orEmpty()

    /** Every session in sidebar order, children under their parents. */
    fun all(): List<SessionUi> = rows(roots)

    /** [roots] and their descendants in sidebar order. */
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
        val hierarchy = session.hierarchy ?: return legacyRoot(session)
        return fullRoot(session, hierarchy)
    }

    private fun fullRoot(session: SessionUi, hierarchy: SessionHierarchyUi): String? {
        if (hierarchy.role != SessionRoleUi.Subsession) return null
        return hierarchy.rootSessionId?.takeIf { id ->
            val root = indexed[id]
            val isFullRoot = root?.hierarchy?.let {
                it.role == SessionRoleUi.Session && it.rootSessionId == id
            } ?: (root != null && legacyRoot(root) == null)
            id != session.id && root?.projectId == session.projectId && isFullRoot
        }
    }

    private fun legacyRoot(session: SessionUi): String? {
        val parent = session.parentChatId?.takeIf { it in indexed } ?: return null
        val seen = mutableSetOf(session.id)
        var ancestor: String? = parent
        var root = parent
        while (ancestor != null && seen.add(ancestor)) {
            val row = indexed[ancestor] ?: break
            if (!row.isHumanInteractive) return null
            root = ancestor
            ancestor = parentLink(row)
        }
        return root.takeIf { ancestor == null }
    }

    private fun parentLink(row: SessionUi): String? = if (row.hierarchy == null) {
        row.parentChatId?.takeIf { it in indexed }
    } else {
        row.hierarchy.rootSessionId?.takeIf { it != row.id && it in indexed }
    }
}
