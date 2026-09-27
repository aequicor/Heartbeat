package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.AppRouteBinding
import io.aequicor.heartbeat.core.navigation.DeepLinkEntry
import io.aequicor.heartbeat.core.navigation.DeepLinkParams
import io.aequicor.heartbeat.core.navigation.DeepLinkResult
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavCommand
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileDeepLinkBinding
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.routeEntry
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationIntegrationTest {

    private fun newContext(): ComponentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() })

    private val StackHost.routes get() = stack.value.items.map { it.configuration.route }

    @Test
    fun `guest tree shows only app routes, profile tree shows both`() = runTest {
        val app = createGraphFactory<TestAppGraph.Factory>().create(PersistedProfile())

        val guest = app.guestNavigation.create(newContext(), initial = listOf(WelcomeRoute), name = "guest")
        guest.navigator.navigate(FeedRoute)
        assertEquals(listOf(WelcomeRoute), guest.routes)
        assertEquals(DeepLinkResult.NoMatch, guest.handleDeepLink("heartbeat://feed"))

        val session = app.profileSessions.open(ProfileId("p1"))
        val profile = (session.graph as ProfileNavigation).navigation
            .create(newContext(), initial = listOf(FeedRoute), name = "profile")
        profile.navigator.navigate(WelcomeRoute)
        assertEquals(listOf(FeedRoute, WelcomeRoute), profile.routes)

        assertEquals(DeepLinkResult.Handled, profile.handleDeepLink("heartbeat://feed"))
        assertEquals(listOf(FeedRoute), profile.routes)
        // the feed component got the injected NavHostFactory and built its nested stack
        val feed = profile.stack.value.active.instance as FeedComponent
        assertEquals(listOf(FeedPostRoute), feed.posts.routes)
    }
}

// ---- guest feature (AppScope) ----

@Serializable
@SerialName("test.welcome")
data object WelcomeRoute : Route

class PlainComponent : NavComponent

@ContributesIntoSet(AppScope::class, binding = binding<AppRouteBinding>())
@Inject
class WelcomeRouteEntry : RouteEntry<WelcomeRoute>(WelcomeRoute::class, WelcomeRoute.serializer()) {
    override fun create(route: WelcomeRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        PlainComponent()
}

// ---- profile feature (ProfileScope) with a nested stack ----

@Serializable
@SerialName("feed")
data object FeedRoute : Route

@Serializable
@SerialName("feed.post")
data object FeedPostRoute : Route

class FeedComponent(context: ComponentContext, navigator: Navigator, hosts: NavHostFactory) : NavComponent {
    val posts: StackHost = hosts.stack(
        context,
        navigator,
        name = "posts",
        initial = listOf(FeedPostRoute),
        local = listOf(routeEntry<FeedPostRoute> { _, _, _ -> PlainComponent() }),
    )
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
class FeedRouteEntry(private val hosts: NavHostFactory) :
    RouteEntry<FeedRoute>(
        FeedRoute::class,
        FeedRoute.serializer(),
    ) {
    override fun create(route: FeedRoute, context: ComponentContext, navigator: Navigator): NavComponent =
        FeedComponent(context, navigator, hosts)
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileDeepLinkBinding>())
@Inject
class FeedDeepLink : DeepLinkEntry("feed") {
    override fun commands(params: DeepLinkParams): List<NavCommand> =
        listOf(NavCommand(FeedRoute, NavOptions(launch = LaunchMode.ReplaceAll)))
}
