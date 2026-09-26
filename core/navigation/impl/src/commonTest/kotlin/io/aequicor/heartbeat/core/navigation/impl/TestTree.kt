package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.SerializableContainer
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import io.aequicor.heartbeat.core.navigation.DeepLinkConfig
import io.aequicor.heartbeat.core.navigation.DeepLinkEntry
import io.aequicor.heartbeat.core.navigation.DeepLinkParams
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavCommand
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTarget
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.PanelsHost
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.RootHost
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.routeEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// ---- routes of fake features ----

@Serializable
@SerialName("home")
data object HomeRoute : Route

@Serializable
@SerialName("list")
data object ListRoute : Route

@Serializable
@SerialName("chat")
data class ChatRoute(val id: String) : Route

@Serializable
@SerialName("chat.settings")
data object ChatSettingsRoute : Route

@Serializable
@SerialName("contacts.pick")
data object PickContactRoute : Route

@Serializable
@SerialName("unregistered")
data object UnregisteredRoute : Route

object PickedContact : ResultContract<String>("contacts.pick", String.serializer())

/** Component of any test route; features that own hosts create them like real components would. */
class TestComponent(val route: Route, val context: ComponentContext, val navigator: Navigator) : NavComponent {
    var stack: StackHost? = null
    var panels: PanelsHost? = null
}

private val hosts = NavHostFactoryImpl()

/** `home` hosts list-details; `chat` hosts its own stack with the local settings screen. */
internal fun testRoutes(chatGlobal: GlobalRoutes = GlobalRoutes.None) = RouteRegistry(
    listOf(
        routeEntry<HomeRoute> { route, ctx, nav ->
            TestComponent(route, ctx, nav).apply { panels = hosts.panels(ctx, nav, "home", main = ListRoute) }
        },
        routeEntry<ListRoute> { route, ctx, nav -> TestComponent(route, ctx, nav) },
        routeEntry<ChatRoute> { route, ctx, nav ->
            TestComponent(route, ctx, nav).apply {
                stack = hosts.stack(
                    ctx,
                    nav,
                    name = "chat",
                    initial = listOf(ConversationRoute(route.id)),
                    local = listOf(
                        routeEntry<ConversationRoute> { r, c, n -> TestComponent(r, c, n) },
                        routeEntry<ChatSettingsRoute> { r, c, n -> TestComponent(r, c, n) },
                    ),
                    global = chatGlobal,
                )
            }
        },
        routeEntry<PickContactRoute> { route, ctx, nav -> TestComponent(route, ctx, nav) },
    ),
)

@Serializable
@SerialName("chat.conversation")
data class ConversationRoute(val id: String) : Route

object TestDeepLinkConfig : DeepLinkConfig {
    override val schemes = setOf("heartbeat")
    override val webHosts = setOf("heartbeat.app")
}

object ChatLink : DeepLinkEntry("chat/{id}") {
    override fun commands(params: DeepLinkParams): List<NavCommand> {
        val id = params.path("id")
        require(id.all { it.isLetterOrDigit() }) { "invalid chat id" }
        return listOf(
            NavCommand(HomeRoute, NavOptions(launch = LaunchMode.ReplaceAll, target = NavTarget.Root)),
            NavCommand(ChatRoute(id), NavOptions(target = NavTarget.Details)),
        )
    }
}

object ChatSettingsLink : DeepLinkEntry("chat/{id}/settings") {
    override fun commands(params: DeepLinkParams): List<NavCommand> =
        ChatLink.commands(params) + NavCommand(ChatSettingsRoute)
}

val testDeepLinks = listOf(ChatLink, ChatSettingsLink)

/** A root host in a fake process: [save] + a new [TestTree] with the saved state emulates process death. */
internal class TestTree(
    saved: SerializableContainer? = null,
    routes: RouteRegistry = testRoutes(),
    initial: List<Route> = listOf(HomeRoute),
) {
    private val stateKeeper = StateKeeperDispatcher(saved)
    val instanceKeeper = InstanceKeeperDispatcher()
    val back = BackDispatcher()
    private val context = DefaultComponentContext(
        lifecycle = LifecycleRegistry().apply { resume() },
        stateKeeper = stateKeeper,
        instanceKeeper = instanceKeeper,
        backHandler = back,
    )

    val root: RootHost = RootNavHostFactoryImpl(routes, DeepLinkRouter(TestDeepLinkConfig, testDeepLinks))
        .create(context, initial)

    /** Serializes the lazy container so tests exercise the same serializers as process restoration. */
    fun save(): SerializableContainer = Json.decodeFromString(
        SerializableContainer.serializer(),
        Json.encodeToString(SerializableContainer.serializer(), stateKeeper.save()),
    )
}

val StackHost.routes: List<Route> get() = stack.value.items.map { it.configuration.route }
val StackHost.top: TestComponent get() = stack.value.active.instance as TestComponent
val PanelsHost.details: TestComponent? get() = panels.value.details?.instance as TestComponent?
val PanelsHost.main: TestComponent get() = panels.value.main.instance as TestComponent
internal val TestTree.home: TestComponent get() = root.stack.value.items.first().instance as TestComponent
