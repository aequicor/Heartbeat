package io.aequicor.heartbeat.core.navigation.impl

import io.aequicor.heartbeat.core.navigation.DeepLinkResult
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.NavTransition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavigationTest {

    @Test
    fun `launch modes shape the root stack`() {
        val tree = TestTree()
        val nav = tree.root.navigator

        nav.navigate(ChatRoute("1"))
        nav.navigate(ChatRoute("1"), NavOptions(launch = LaunchMode.SingleTop))
        assertEquals(listOf(HomeRoute, ChatRoute("1")), tree.root.routes)

        val chat1 = tree.root.top
        nav.navigate(PickContactRoute)
        nav.navigate(ChatRoute("1"), NavOptions(launch = LaunchMode.BringToFront))
        assertEquals(listOf(HomeRoute, PickContactRoute, ChatRoute("1")), tree.root.routes)
        assertSame(chat1, tree.root.top, "bring to front keeps the component")

        nav.navigate(ChatRoute("2"), NavOptions(launch = LaunchMode.ReplaceCurrent))
        assertEquals(listOf(HomeRoute, PickContactRoute, ChatRoute("2")), tree.root.routes)

        nav.navigate(HomeRoute, NavOptions(launch = LaunchMode.ReplaceAll))
        assertEquals(listOf(HomeRoute), tree.root.routes)
    }

    @Test
    fun `local routes stay in the nested stack while global ones go up to the root`() {
        val tree = TestTree()
        tree.root.navigator.navigate(ChatRoute("1"))
        val chat = tree.root.top
        val chatStack = checkNotNull(chat.stack)

        chatStack.top.navigator.navigate(ChatSettingsRoute)
        assertEquals(listOf(ConversationRoute("1"), ChatSettingsRoute), chatStack.routes)

        chatStack.top.navigator.navigate(PickContactRoute)
        assertEquals(listOf(HomeRoute, ChatRoute("1"), PickContactRoute), tree.root.routes)
        assertEquals(2, chatStack.routes.size)
    }

    @Test
    fun `a nested stack accepting global routes keeps them`() {
        val tree = TestTree(routes = testRoutes(chatGlobal = GlobalRoutes.Only(setOf(PickContactRoute::class))))
        tree.root.navigator.navigate(ChatRoute("1"))
        val chatStack = checkNotNull(tree.root.top.stack)

        chatStack.top.navigator.navigate(PickContactRoute)
        assertEquals(listOf(ConversationRoute("1"), PickContactRoute), chatStack.routes)

        chatStack.top.navigator.navigate(PickContactRoute, NavOptions(target = NavTarget.Root))
        assertEquals(listOf(HomeRoute, ChatRoute("1"), PickContactRoute), tree.root.routes)
    }

    @Test
    fun `details target opens next to the list and replaces previous details`() {
        val tree = TestTree()
        val panels = checkNotNull(tree.home.panels)

        panels.main.navigator.navigate(ChatRoute("1"), NavOptions(target = NavTarget.Details))
        panels.main.navigator.navigate(ChatRoute("2"), NavOptions(target = NavTarget.Details))
        assertEquals(ChatRoute("2"), panels.details?.route)
        assertEquals(listOf(HomeRoute), tree.root.routes)

        // a global route from inside the details goes to the root, full screen
        checkNotNull(panels.details?.stack).top.navigator.navigate(PickContactRoute)
        assertEquals(listOf(HomeRoute, PickContactRoute), tree.root.routes)

        tree.root.onBack()
        panels.onBack()
        assertNull(panels.details)
    }

    @Test
    fun `closing the last entry of a nested stack closes its owner`() {
        val tree = TestTree()
        tree.root.navigator.navigate(ChatRoute("1"))
        val chatStack = checkNotNull(tree.root.top.stack)

        chatStack.top.navigator.close()

        assertEquals(listOf(HomeRoute), tree.root.routes)
    }

    @Test
    fun `system back pops the innermost stack first`() {
        val tree = TestTree()
        tree.root.navigator.navigate(ChatRoute("1"))
        val chatStack = checkNotNull(tree.root.top.stack)
        chatStack.top.navigator.navigate(ChatSettingsRoute)

        assertTrue(tree.back.back())
        assertEquals(listOf(ConversationRoute("1")), chatStack.routes)
        assertTrue(tree.back.back())
        assertEquals(listOf(HomeRoute), tree.root.routes)
        assertFalse(tree.back.back())
    }

    @Test
    fun `result is delivered to the requester and consumed once`() = runTest {
        val tree = TestTree()
        tree.root.navigator.navigate(ChatRoute("1"))
        val requester = tree.root.top.stack!!.top.navigator

        requester.navigateForResult(PickContactRoute, PickedContact)
        tree.root.top.navigator.finishWithResult(PickedContact, "alice")

        assertEquals(listOf(HomeRoute, ChatRoute("1")), tree.root.routes)
        assertEquals("alice", requester.results(PickedContact).first())
        assertTrue(tree.root.top.stack!!.top.navigator.results(PickedContact).isEmptyNow())
    }

    @Test
    fun `stack with nested hosts and pending results survives process death`() = runTest {
        val before = TestTree()
        before.root.navigator.navigate(ChatRoute("7"), NavOptions(transition = NavTransition.Expand("chat:7")))
        before.root.top.stack!!.top.navigator.navigate(ChatSettingsRoute)
        before.home.panels!!.main.navigator.navigate(ChatRoute("3"), NavOptions(target = NavTarget.Details))
        val requester = before.root.top.stack!!.top.navigator
        requester.navigateForResult(PickContactRoute, PickedContact)
        before.root.top.navigator.finishWithResult(PickedContact, "bob")

        val after = TestTree(saved = before.save())

        assertEquals(listOf(HomeRoute, ChatRoute("7")), after.root.routes)
        assertEquals(NavTransition.Expand("chat:7"), after.root.stack.value.active.configuration.transition)
        val chatStack = checkNotNull(after.root.top.stack)
        assertEquals(listOf(ConversationRoute("7"), ChatSettingsRoute), chatStack.routes)
        assertEquals(ChatRoute("3"), after.home.panels!!.details?.route)
        assertEquals("bob", chatStack.top.navigator.results(PickedContact).first())
    }

    @Test
    fun `results of a destroyed entry are dropped`() = runTest {
        val tree = TestTree()
        tree.root.navigator.navigate(ChatRoute("1"))
        val requester = tree.root.top.navigator
        requester.navigateForResult(PickContactRoute, PickedContact)
        tree.root.top.navigator.finishWithResult(PickedContact, "carol")

        requester.close()
        tree.root.navigator.navigate(ChatRoute("1"))

        assertTrue(tree.root.top.navigator.results(PickedContact).isEmptyNow())
    }

    @Test
    fun `deep link descends into hosts created by previous commands`() {
        val tree = TestTree()
        tree.root.navigator.navigate(PickContactRoute)

        assertEquals(DeepLinkResult.Handled, tree.root.handleDeepLink("heartbeat://chat/42/settings?utm=x"))

        assertEquals(listOf(HomeRoute), tree.root.routes)
        val details = checkNotNull(tree.home.panels!!.details)
        assertEquals(ChatRoute("42"), details.route)
        assertEquals(listOf(ConversationRoute("42"), ChatSettingsRoute), details.stack!!.routes)
    }

    @Test
    fun `deep links from foreign origins or with invalid params are rejected`() {
        val tree = TestTree()

        assertEquals(DeepLinkResult.Handled, tree.root.handleDeepLink("https://heartbeat.app/chat/1"))
        assertEquals(DeepLinkResult.Rejected, tree.root.handleDeepLink("https://evil.example/chat/1"))
        assertEquals(DeepLinkResult.Rejected, tree.root.handleDeepLink("other://chat/1"))
        assertEquals(DeepLinkResult.Rejected, tree.root.handleDeepLink("heartbeat://chat/%2E%2E"))
        assertEquals(DeepLinkResult.Rejected, tree.root.handleDeepLink("heartbeat://chat/%zz"))
        assertEquals(DeepLinkResult.NoMatch, tree.root.handleDeepLink("heartbeat://billing/1"))
    }

    @Test
    fun `unregistered route is ignored`() {
        val tree = TestTree()

        tree.root.navigator.navigate(UnregisteredRoute)

        assertEquals(listOf(HomeRoute), tree.root.routes)
    }

    @Test
    fun `percent decoding handles UTF-8 and rejects malformed input`() {
        assertEquals("чат 1", percentDecode("%D1%87%D0%B0%D1%82+1", plusIsSpace = true))
        assertEquals("a+b", percentDecode("a+b", plusIsSpace = false))
        assertNull(percentDecode("%4", plusIsSpace = false))
        assertNull(percentDecode("%+F", plusIsSpace = false))
        assertNotNull(percentDecode("plain", plusIsSpace = false))
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.isEmptyNow(): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(1) { first() } == null
