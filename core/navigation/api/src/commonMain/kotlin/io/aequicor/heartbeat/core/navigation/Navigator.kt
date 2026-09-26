package io.aequicor.heartbeat.core.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/**
 * Navigation from one stack entry. Every component created for a [Route] gets its own navigator; requests are
 * resolved from the caller's host up to the root (see [NavTarget]). Main thread only.
 */
public interface Navigator {
    /** Opens [route]. A route no host can show is logged as an error and ignored. */
    public fun navigate(route: Route, options: NavOptions = NavOptions())

    /**
     * Opens [route] expecting a result of [contract]; the opened entry answers with [finishWithResult],
     * the result arrives in [results] of this navigator. Closing the entry by "back" gives no result.
     * With [LaunchMode.SingleTop] or [LaunchMode.BringToFront], an equal entry is replaced with a fresh
     * component so this request cannot inherit another caller's result address. Ordinary [navigate] keeps
     * the component according to the launch mode.
     */
    public fun <R : Any> navigateForResult(
        route: Route,
        contract: ResultContract<R>,
        options: NavOptions = NavOptions(),
    )

    /**
     * Results of [contract] addressed to this entry. A result that arrives while nobody collects is kept
     * (also across process death) and delivered on the next collection, then removed.
     */
    public fun <R : Any> results(contract: ResultContract<R>): Flow<R>

    /** Delivers [result] to the entry that opened this one via [navigateForResult], then [close]s this entry. */
    public fun <R : Any> finishWithResult(contract: ResultContract<R>, result: R)

    /** Removes this entry. The last entry of a nested host closes the entry that owns the host. */
    public fun close()
}

/**
 * Result type of a route. Declared by the opened feature in its api next to the route:
 * ```
 * public object PickContactResult : ResultContract<ContactRef>("contacts.pick", ContactRef.serializer())
 * ```
 */
public open class ResultContract<R : Any>(
    /** Unique name of the contract. */
    public val name: String,
    /** Serializer of the result: pending results are saved with the navigation state. */
    public val serializer: KSerializer<R>,
)
