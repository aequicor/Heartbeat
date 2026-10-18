package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.QuickActionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceInputBinding
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * What the composer of a host draws for the canonical app of that host: its visible quick [actions], the [notice] of
 * the last failure (a quick action may fail while the app screen is closed) and whether the owner's methods run
 * ([isAvailable]). [authority] identifies the app execution that drew them; input sent back with it is dropped once
 * the app moved to another context.
 */
@Immutable
internal data class AppQuickActionsUi(
    val actions: ImmutableList<QuickActionUi> = persistentListOf(),
    val notice: NoticeUi? = null,
    val isAvailable: Boolean = false,
    val authority: SurfaceInputBinding? = null,
) {
    /** Whether there is nothing to draw: no action and no notice. */
    val isEmpty: Boolean get() = actions.isEmpty() && notice == null

    override fun toString(): String = "AppQuickActionsUi(actions=${actions.size}, hasNotice=${notice != null})"
}

/** The app of one host as its composer sees it: what to draw, and where its input goes. */
internal interface AppQuickActionsSource {
    /** The current projection; empty while the host has no live app. */
    val state: StateFlow<AppQuickActionsUi>

    /** Passes [intent] to the store of the app, which drops it unless its binding is still current. */
    fun send(intent: SurfaceIntent)
}

/**
 * The meeting point of the retained app of a host and the composer slot of the same host. They live in different
 * graphs of the profile and must not share a surface store, so the app publishes a projection here and the slot
 * reads it and sends input back.
 */
internal interface AppQuickActionBoard {
    /** Publishes [source] as the app of [host] until the handle is disposed; a newer source replaces an older one. */
    fun attach(host: UiHostId, source: AppQuickActionsSource): DisposableHandle

    /** The projection of the app of [host]; empty while none is attached. */
    fun observe(host: UiHostId): Flow<AppQuickActionsUi>

    /** Passes [intent] to the app of [host], if one is attached. */
    fun send(host: UiHostId, intent: SurfaceIntent)
}

/** The board of the profile. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProfileAppQuickActionBoard : AppQuickActionBoard {
    private val log = Log.tag("ServiceUiQuickActions")
    private val sources = MutableStateFlow<PersistentMap<UiHostId, AppQuickActionsSource>>(persistentMapOf())

    override fun attach(host: UiHostId, source: AppQuickActionsSource): DisposableHandle {
        log.d { "App of a host attached to the quick action board" }
        sources.update { it.put(host, source) }
        return DisposableHandle {
            log.d { "App of a host detached from the quick action board" }
            sources.update { current -> if (current[host] === source) current.remove(host) else current }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(host: UiHostId): Flow<AppQuickActionsUi> = sources.map { it[host] }
        .distinctUntilChanged()
        .flatMapLatest { source -> source?.state ?: flowOf(AppQuickActionsUi()) }

    override fun send(host: UiHostId, intent: SurfaceIntent) {
        sources.value[host]?.send(intent)
    }
}
