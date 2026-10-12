package io.aequicor.heartbeat.feature.aistudio.impl.presentation.component

import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.researchchat.api.ResearchChatRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioWorkspaceNavigationTest {
    private val target = EngineTarget(KoogEngineId, EngineBindingId("binding"), ModelId("model"))

    @Test
    fun `closing research leaves only the chat panes so back never returns to it`() {
        val stack = WorkspaceStack()
        val navigation = StudioWorkspaceNavigation(stack)

        navigation.openResearch(target)
        assertEquals(listOf(StudioChatRoute, ResearchChatRoute(target)), stack.routes)
        navigation.closeChatArea()

        assertEquals(listOf<Route>(StudioChatRoute), stack.routes)
        // "Back" pops only while more than one entry is left: the closed research cannot come back.
        stack.back()
        assertEquals(listOf<Route>(StudioChatRoute), stack.routes)
    }

    @Test
    fun `repeated research cycles never grow the workspace stack`() {
        val stack = WorkspaceStack()
        val navigation = StudioWorkspaceNavigation(stack)

        repeat(3) {
            navigation.openResearch(target)
            navigation.closeChatArea()
        }

        assertEquals(listOf<Route>(StudioChatRoute), stack.routes)
    }

    /** Applies [LaunchMode]s as documented in `core:navigation`; the base entry is the studio chat. */
    private class WorkspaceStack : Navigator {
        val routes = mutableListOf<Route>(StudioChatRoute)

        override fun navigate(route: Route, options: NavOptions) {
            when (options.launch) {
                LaunchMode.Push -> routes += route

                LaunchMode.SingleTop -> if (routes.last() != route) routes += route

                LaunchMode.BringToFront -> {
                    routes.remove(route)
                    routes += route
                }

                LaunchMode.ReplaceCurrent -> routes[routes.lastIndex] = route

                LaunchMode.ReplaceAll -> {
                    routes.clear()
                    routes += route
                }
            }
        }

        fun back() {
            if (routes.size > 1) routes.removeAt(routes.lastIndex)
        }

        override fun <R : Any> navigateForResult(route: Route, contract: ResultContract<R>, options: NavOptions) =
            error("Workspace navigation expects no results")

        override fun <R : Any> results(contract: ResultContract<R>): Flow<R> = emptyFlow()

        override fun <R : Any> finishWithResult(contract: ResultContract<R>, result: R) = error("Not an entry")

        override fun close() = error("The studio chat panes are never closed by the workspace navigation")
    }
}
