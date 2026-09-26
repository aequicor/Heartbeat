package io.aequicor.heartbeat.core.navigation

import com.arkivanov.decompose.ComponentContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/**
 * Address of a screen or a feature. Implementations are `@Serializable` with a stable `@SerialName`:
 * the route is saved in the navigation state and restored after process death.
 *
 * Public routes (other features open them) live in the feature's api, internal screens — in its impl:
 * ```
 * @Serializable @SerialName("chat") public data class ChatRoute(val chatId: String) : Route
 * ```
 * Carry ids, not objects; no secrets or personal data — routes are persisted and logged.
 */
public interface Route

/** Marker of a component created for a [Route]. UI modules render it (see `ComposableComponent`). */
public interface NavComponent

/**
 * Creates the component of routes of type [R]. Contributed by the owning feature's impl into one of the two
 * registries — before sign-in (`AppScope`) or within a profile (`ProfileScope`):
 * ```
 * @ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>()) // or AppRouteBinding
 * @Inject
 * internal class ChatRouteEntry(private val factory: ChatRootComponent.Factory) :
 *     RouteEntry<ChatRoute>(ChatRoute::class, ChatRoute.serializer()) {
 *     override fun create(route: ChatRoute, context: ComponentContext, navigator: Navigator) =
 *         factory.create(context, route, navigator)
 * }
 * ```
 * Internal screens of a feature are passed to its own host as local entries (see [routeEntry]).
 */
public abstract class RouteEntry<R : Route>(
    /** Runtime class of the route; a route is matched to its entry by exact class. */
    public val routeClass: KClass<R>,
    /** Serializer of the route; its `serialName` is the stable type id in saved state and logs. */
    public val serializer: KSerializer<R>,
) {
    /** Stable type id of the route. */
    public val typeName: String get() = serializer.descriptor.serialName

    /** Creates the component for [route]; [navigator] is the navigator of this very stack entry. */
    public abstract fun create(route: R, context: ComponentContext, navigator: Navigator): NavComponent
}

/** Local [RouteEntry] without a DI class — for internal screens of a feature. */
public inline fun <reified R : Route> routeEntry(
    crossinline create: (route: R, context: ComponentContext, navigator: Navigator) -> NavComponent,
): RouteEntry<R> = object : RouteEntry<R>(R::class, serializer<R>()) {
    override fun create(route: R, context: ComponentContext, navigator: Navigator): NavComponent =
        create(route, context, navigator)
}
