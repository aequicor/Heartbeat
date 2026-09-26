package io.aequicor.heartbeat.core.navigation.impl

/** Shared state of one navigation tree (one root host and every host below it). */
internal data class NavTree(
    /** Global routes of the tree: app routes, plus profile routes in a profile tree. */
    val routes: RouteRegistry,
    val deepLinks: DeepLinkRouter,
    val results: ResultStore,
)

internal const val LOG_TAG = "NAV"
