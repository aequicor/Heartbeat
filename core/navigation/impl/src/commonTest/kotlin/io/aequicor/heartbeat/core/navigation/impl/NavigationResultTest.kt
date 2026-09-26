package io.aequicor.heartbeat.core.navigation.impl

import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.Navigator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class NavigationResultTest {
    @Test
    fun `bring to front for result replaces the existing entry and addresses the new caller`() = runTest {
        val tree = TestTree()
        tree.home.navigator.navigateForResult(PickContactRoute, PickedContact)
        val originalPicker = tree.root.top
        tree.root.navigator.navigate(ChatRoute("1"))
        val requester = tree.root.top.navigator

        requester.navigateForResult(PickContactRoute, PickedContact, NavOptions(launch = LaunchMode.BringToFront))

        assertNotSame(originalPicker, tree.root.top)
        assertEquals(listOf(HomeRoute, ChatRoute("1"), PickContactRoute), tree.root.routes)
        tree.root.top.navigator.finishWithResult(PickedContact, "alice")
        assertEquals("alice", requester.pickedContactOrNull())
        assertNull(tree.home.navigator.pickedContactOrNull())
    }

    @Test
    fun `single top for result replaces the request and preserves it through process death`() = runTest {
        val before = TestTree()
        before.root.navigator.navigate(ChatRoute("1"))
        val requester = before.root.top.navigator
        before.home.navigator.navigateForResult(PickContactRoute, PickedContact)
        val originalPicker = before.root.top

        requester.navigateForResult(PickContactRoute, PickedContact, NavOptions(launch = LaunchMode.SingleTop))

        assertNotSame(originalPicker, before.root.top)
        assertEquals(listOf(HomeRoute, ChatRoute("1"), PickContactRoute), before.root.routes)
        val after = TestTree(saved = before.save())
        after.root.top.navigator.finishWithResult(PickedContact, "bob")
        assertEquals("bob", after.root.top.navigator.pickedContactOrNull())
        assertNull(after.home.navigator.pickedContactOrNull())
    }

    @Test
    fun `reopening details for result addresses the new caller after process death`() = runTest {
        for (launch in listOf(LaunchMode.SingleTop, LaunchMode.BringToFront)) {
            val before = TestTree()
            val panels = checkNotNull(before.home.panels)
            panels.navigator.navigateForResult(PickContactRoute, PickedContact, NavOptions(target = NavTarget.Details))
            val originalPicker = panels.details

            panels.main.navigator.navigateForResult(
                PickContactRoute,
                PickedContact,
                NavOptions(launch = launch, target = NavTarget.Details),
            )

            assertNotSame(originalPicker, panels.details)
            val after = TestTree(saved = before.save())
            val restoredPanels = checkNotNull(after.home.panels)
            restoredPanels.details!!.navigator.finishWithResult(PickedContact, "carol")
            assertEquals("carol", restoredPanels.main.navigator.pickedContactOrNull())
            assertNull(after.home.navigator.pickedContactOrNull())
        }
    }
}

private suspend fun Navigator.pickedContactOrNull(): String? = withTimeoutOrNull(1) { results(PickedContact).first() }
