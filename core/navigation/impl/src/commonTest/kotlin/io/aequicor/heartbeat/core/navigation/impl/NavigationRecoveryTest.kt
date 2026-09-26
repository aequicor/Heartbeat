package io.aequicor.heartbeat.core.navigation.impl

import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.navigation.routeEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationRecoveryTest {
    @Test
    fun `unknown route targeted at root is ignored`() {
        val tree = TestTree()

        tree.root.navigator.navigate(UnregisteredRoute, NavOptions(target = NavTarget.Root))

        assertEquals(listOf(HomeRoute), tree.root.routes)
    }

    @Test
    fun `local route targeted at root is ignored without changing either host`() {
        val tree = TestTree()
        tree.root.navigator.navigate(ChatRoute("1"))
        val nested = checkNotNull(tree.root.top.stack)

        nested.top.navigator.navigate(ChatSettingsRoute, NavOptions(target = NavTarget.Root))

        assertEquals(listOf(HomeRoute, ChatRoute("1")), tree.root.routes)
        assertEquals(listOf(ConversationRoute("1")), nested.routes)
    }

    @Test
    fun `removed stack route restarts initial routes and clears orphaned results`() {
        val before = TestTree(routes = recoveryRoutes())
        before.home.navigator.navigateForResult(PickContactRoute, PickedContact)
        before.root.top.navigator.finishWithResult(PickedContact, "orphan")
        before.root.navigator.navigate(PickContactRoute)
        val oldHomeId = before.root.stack.value.backStack.first().configuration.id

        val after = TestTree(saved = before.save(), routes = recoveryRoutes(picker = null))

        assertEquals(listOf(HomeRoute), after.root.routes)
        assertNotEquals(oldHomeId, after.root.stack.value.active.configuration.id)
        assertTrue((after.root as HostNode).tree.results.snapshot().results.isEmpty())
    }

    @Test
    fun `incompatible route arguments restart the initial stack`() {
        val before = TestTree(routes = recoveryRoutes())
        before.root.navigator.navigate(PickContactRoute)
        val updatedPicker = routeEntry<UpdatedPickerRoute> { route, ctx, nav -> TestComponent(route, ctx, nav) }

        val after = TestTree(saved = before.save(), routes = recoveryRoutes(picker = updatedPicker))

        assertEquals(listOf(HomeRoute), after.root.routes)
    }

    @Test
    fun `removed details route resets its host and keeps owner results`() = runTest {
        val before = TestTree(routes = recoveryRoutes())
        val panels = checkNotNull(before.home.panels)
        panels.main.navigator.navigateForResult(PickContactRoute, PickedContact)
        before.root.top.navigator.finishWithResult(PickedContact, "orphaned main result")
        before.home.navigator.navigateForResult(PickContactRoute, PickedContact)
        before.root.top.navigator.finishWithResult(PickedContact, "owner result")
        panels.main.navigator.navigate(PickContactRoute, NavOptions(target = NavTarget.Details))
        val oldHomeId = before.root.stack.value.active.configuration.id

        val after = TestTree(saved = before.save(), routes = recoveryRoutes(picker = null))

        assertEquals(oldHomeId, after.root.stack.value.active.configuration.id)
        assertNull(after.home.panels!!.details)
        assertEquals(ListRoute, after.home.panels!!.main.route)
        assertEquals("owner result", after.home.navigator.results(PickedContact).first())
        assertTrue((after.root as HostNode).tree.results.snapshot().results.isEmpty())
    }
}

private fun recoveryRoutes(
    picker: RouteEntry<*>? = routeEntry<PickContactRoute> { route, ctx, nav -> TestComponent(route, ctx, nav) },
): RouteRegistry = RouteRegistry(
    listOfNotNull(
        routeEntry<HomeRoute> { route, ctx, nav ->
            TestComponent(route, ctx, nav).apply {
                panels = NavHostFactoryImpl().panels(ctx, nav, "home", main = ListRoute)
            }
        },
        routeEntry<ListRoute> { route, ctx, nav -> TestComponent(route, ctx, nav) },
        picker,
    ),
)

@Serializable
@SerialName("contacts.pick")
private data class UpdatedPickerRoute(val requiredArgument: String) : Route
