package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.panels.Panels
import com.arkivanov.decompose.router.panels.PanelsNavigation
import com.arkivanov.decompose.router.panels.activateDetails
import com.arkivanov.decompose.router.panels.childPanels
import com.arkivanov.decompose.router.panels.dismissDetails
import com.arkivanov.decompose.router.panels.setMode
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.statekeeper.SerializableContainer
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.PanelsHost
import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.NothingSerializer

/**
 * [PanelsHost] over Decompose `childPanels` (main + details). Opening a route replaces the details entry;
 * "back" dismisses details. `childPanels` is experimental in Decompose 3.5.
 */
@OptIn(ExperimentalDecomposeApi::class, ExperimentalSerializationApi::class) // childPanels signature
internal class PanelsHostImpl(params: HostParams, main: Route, details: Route?) :
    HostNode(params),
    PanelsHost {

    private val navigation = PanelsNavigation<Entry, Entry, Nothing>()

    override val panels: Value<ChildPanels<NavEntry, NavComponent, NavEntry, NavComponent, Nothing, Nothing>>

    init {
        requireShowable(main)
        details?.let(::requireShowable)
        val entrySerializer = EntrySerializer(lookup)
        val serializer = Panels.serializer(entrySerializer, entrySerializer, NothingSerializer())
        panels = context.childPanels(
            source = navigation,
            savePanels = { SerializableContainer(it, serializer) },
            restorePanels = { restoreState(it, serializer) },
            initialPanels = {
                Panels(
                    main = newEntry(main, NavTransition.Default),
                    details = details?.let { newEntry(it, NavTransition.Default) },
                )
            },
            key = key,
            handleBackButton = true,
            mainFactory = ::createChild,
            detailsFactory = ::createChild,
        )
    }

    private val mainEntry: Entry get() = panels.value.main.configuration as Entry
    private val detailsEntry: Entry? get() = panels.value.details?.configuration as Entry?

    /** Nearest requests stop here only for local routes (they open in details); global ones go up. */
    override fun acceptsNearest(route: Route): Boolean = localRoutes.contains(route::class)

    override fun open(route: Route, options: NavOptions, request: ResultRequest?) {
        if (options.launch == LaunchMode.SingleTop && detailsEntry?.route == route && request == null) {
            log.i { "$path: details ${typeOf(route)} already shown, ignored" }
            return
        }
        navigation.activateDetails(newEntry(route, options.transition, request)) { _, _ ->
            log.i { "$path: details ${typeOf(route)}" }
        }
    }

    override fun remove(id: String) {
        when (id) {
            detailsEntry?.id -> navigation.dismissDetails { _, _ -> log.i { "$path: details closed" } }
            mainEntry.id -> owner?.close()
            else -> log.d { "$path: close of a removed entry $id ignored" }
        }
    }

    override fun onBack() {
        if (detailsEntry != null) navigation.dismissDetails { _, _ -> log.i { "$path: back, details closed" } }
    }

    override fun setMode(mode: ChildPanelsMode) {
        if (panels.value.mode == mode) return
        navigation.setMode(mode) { _, _ -> log.i { "$path: mode $mode" } }
    }

    override fun activeEntryId(): String = (detailsEntry ?: mainEntry).id
}
