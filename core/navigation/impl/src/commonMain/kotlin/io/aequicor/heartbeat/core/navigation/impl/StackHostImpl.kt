package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.statekeeper.SerializableContainer
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.StackHost
import kotlinx.serialization.builtins.ListSerializer

/** [StackHost] over Decompose `childStack`; "back" pops while more than one entry is left. */
internal open class StackHostImpl(params: HostParams, private val global: GlobalRoutes, initial: List<Route>) :
    HostNode(params),
    StackHost {

    private val navigation = StackNavigation<Entry>()

    final override val stack: Value<ChildStack<NavEntry, NavComponent>>

    init {
        require(initial.isNotEmpty()) { "$path: a stack needs at least one initial route" }
        initial.forEach(::requireShowable)
        val serializer = ListSerializer(EntrySerializer(lookup))
        stack = context.childStack(
            source = navigation,
            saveStack = { SerializableContainer(it, serializer) },
            restoreStack = { restoreState(it, serializer) },
            initialStack = { initial.map { newEntry(it, NavTransition.Default) } },
            key = key,
            handleBackButton = true,
            childFactory = ::createChild,
        )
    }

    private val entries: List<Entry> get() = stack.value.items.map { it.configuration as Entry }

    override fun acceptsNearest(route: Route): Boolean = when {
        localRoutes.contains(route::class) -> true

        !tree.routes.contains(route::class) -> false

        else -> when (global) {
            GlobalRoutes.All -> true
            GlobalRoutes.None -> false
            is GlobalRoutes.Only -> route::class in global.routes
        }
    }

    override fun open(route: Route, options: NavOptions, request: ResultRequest?) {
        val entry = newEntry(route, options.transition, request)
        navigation.navigate(
            transformer = { stack -> stack.launch(entry, options.launch) },
            onComplete = { new, old ->
                if (new == old) {
                    log.i { "$path: ${options.launch} ${typeOf(route)} — already on top, ignored" }
                } else {
                    log.i { "$path: ${options.launch} ${typeOf(route)}; stack=${names(new)}" }
                }
            },
        )
    }

    override fun remove(id: String) {
        val current = entries
        when {
            current.none { it.id == id } -> log.d { "$path: close of a removed entry $id ignored" }

            current.size > 1 -> navigation.navigate(
                transformer = { stack -> stack.filterNot { it.id == id } },
                onComplete = { new, _ -> log.i { "$path: close $id; stack=${names(new)}" } },
            )

            else -> owner?.close() ?: log.w { "$path: the last entry of the root cannot be closed" }
        }
    }

    override fun onBack() {
        if (entries.size > 1) {
            navigation.navigate(
                transformer = { it.dropLast(1) },
                onComplete = { new, _ -> log.i { "$path: back; stack=${names(new)}" } },
            )
        }
    }

    override fun activeEntryId(): String = stack.value.active.configuration.id
}

private fun List<Entry>.launch(entry: Entry, mode: LaunchMode): List<Entry> = when (mode) {
    LaunchMode.Push -> this + entry

    LaunchMode.SingleTop -> when {
        lastOrNull()?.route != entry.route -> this + entry
        entry.request != null -> dropLast(1) + entry
        else -> this
    }

    LaunchMode.BringToFront -> {
        val existing = firstOrNull { it.route == entry.route }
        if (existing == null) this + entry else this - existing + if (entry.request == null) existing else entry
    }

    LaunchMode.ReplaceCurrent -> dropLast(1) + entry

    LaunchMode.ReplaceAll -> listOf(entry)
}
