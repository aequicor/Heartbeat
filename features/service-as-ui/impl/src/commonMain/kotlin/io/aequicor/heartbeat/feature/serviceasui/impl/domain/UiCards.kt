package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** What a card draws: a spec the agent wrote for it, or a surface of the library. */
internal sealed interface UiCardSource {
    /** A spec without an owner: it cannot call methods, load data or send prompts. */
    data class Inline(val spec: String) : UiCardSource {
        override fun toString(): String = "Inline(chars=${spec.length})"
    }

    /** The library surface [surface], drawn with the arguments of the card. */
    data class Surface(val surface: UiSurfaceId) : UiCardSource
}

/** A card the agent showed with `ui_show` in a response of [chat], by its tool call [callId]. */
internal data class UiCard(
    val id: String,
    val chat: SessionRef,
    val callId: String?,
    val title: String,
    val source: UiCardSource,
    val args: JsonObject,
    val createdAt: Instant,
) {
    override fun toString(): String = "UiCard(id=$id, source=$source)"
}

/** How storing a card ended. */
internal sealed interface UiCardAdded {
    /** Stored; the [evicted] oldest cards of the profile made room for it. */
    data class Stored(val evicted: Int) : UiCardAdded

    /** Not stored: its chat already holds [UiCardLimits.PER_CHAT] cards. */
    data object ChatFull : UiCardAdded
}

/** Bounds of the stored cards. */
internal object UiCardLimits {
    /** Cards of one chat; the agent gets an error beyond it. */
    const val PER_CHAT: Int = 64

    /** Cards of a profile; the oldest are evicted beyond it and show a placeholder in their responses. */
    const val PER_PROFILE: Int = 2048
}

/** Durable cards of the profile: they stay until the profile limit evicts the oldest. */
internal interface UiCardStorage {
    /** Stores [card] unless its chat is full, evicting the oldest cards of the profile beyond its limit. */
    suspend fun add(card: UiCard): UiCardAdded

    /** The card [id], or null once it was evicted, never stored or cannot be read. */
    fun observe(id: String): Flow<UiCard?>
}
