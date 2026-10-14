package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardAdded
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardLimits
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Cards in the feature's profile database; an unreadable row reads as a missing card. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class RoomUiCards(
    @ForScope(ProfileScope::class) private val stores: DataStores,
) : UiCardStorage {
    private val log = Log.tag("ServiceUiCards")
    private val dao by lazy { stores.database(ServiceUiDatabaseSpec).cards() }

    override suspend fun add(card: UiCard): UiCardAdded {
        val stored = dao.add(card.row(), UiCardLimits.PER_CHAT, UiCardLimits.PER_PROFILE)
        return if (stored == null) {
            log.i { "Card ${card.id} not stored: its chat holds ${UiCardLimits.PER_CHAT} cards" }
            UiCardAdded.ChatFull
        } else {
            log.i { "Card ${stored.id} stored, ${stored.evicted} oldest evicted" }
            UiCardAdded.Stored(stored.id, stored.evicted)
        }
    }

    override fun observe(id: String): Flow<UiCard?> = dao.observe(id).map { it?.card() }

    override suspend fun latest(chat: SessionRef, surface: UiSurfaceId): UiCard? =
        dao.latest(Json.encodeToString(SessionRef.serializer(), chat), surface.value)?.card()

    private fun UiCard.row() = CardEntity(
        id = id,
        chat = Json.encodeToString(SessionRef.serializer(), chat),
        callId = callId,
        title = title,
        spec = (source as? UiCardSource.Inline)?.spec,
        surface = (source as? UiCardSource.Surface)?.surface?.value,
        args = Json.encodeToString(JsonObject.serializer(), args),
        createdAt = createdAt.toEpochMilliseconds(),
    )

    private fun CardEntity.card(): UiCard? = try {
        val source = spec?.let { UiCardSource.Inline(it) } ?: surface?.let { UiCardSource.Surface(UiSurfaceId(it)) }
        source?.let {
            UiCard(
                id = id,
                chat = Json.decodeFromString(SessionRef.serializer(), chat),
                callId = callId,
                title = title,
                source = it,
                args = Json.decodeFromString(JsonObject.serializer(), args),
                createdAt = Instant.fromEpochMilliseconds(createdAt),
            )
        }
    } catch (e: IllegalArgumentException) {
        // Also a kotlinx SerializationException: a row written by another version or damaged on disk.
        log.w(e.redactedFailure()) { "Card $id cannot be read" }
        null
    }
}
