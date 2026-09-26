package io.aequicor.heartbeat.core.navigation.impl

import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** Configuration of a Decompose child: one entry of a host. Unique by [id], so equal routes may repeat. */
internal data class Entry(
    override val id: String,
    override val route: Route,
    override val transition: NavTransition,
    /** Who waits for a result of this entry, if it was opened for a result. */
    val request: ResultRequest?,
) : NavEntry {
    override fun toString(): String = "Entry($id)" // routes may carry ids — never print them implicitly
}

/** The entry at [requester] path waits for a result of [contract]. */
@Serializable
internal data class ResultRequest(val requester: String, val contract: String)

internal fun newEntry(route: Route, transition: NavTransition, request: ResultRequest? = null): Entry = Entry(
    id = Uuid.random().toHexString().take(ENTRY_ID_LENGTH),
    route = route,
    transition = transition,
    request = request,
)

/**
 * Saves an [Entry] as `{type, route JSON}`: the route serializer is found by the stable type name in the host's
 * [RouteLookup], so no polymorphic registration is needed. An unknown type (feature removed between versions)
 * fails the restore with [SerializationException] — the host catches it and uses its initial configuration.
 */
internal class EntrySerializer(private val routes: RouteLookup) : KSerializer<Entry> {

    override val descriptor = SavedEntry.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Entry) {
        val entry = routes.forRoute(value.route)
            ?: throw SerializationException("no route entry for ${value.route::class}")
        val saved = SavedEntry(
            id = value.id,
            type = entry.typeName,
            route = RouteJson.encodeToString(entry.routeSerializer, value.route),
            transition = value.transition,
            request = value.request,
        )
        encoder.encodeSerializableValue(SavedEntry.serializer(), saved)
    }

    override fun deserialize(decoder: Decoder): Entry {
        val saved = decoder.decodeSerializableValue(SavedEntry.serializer())
        val entry = routes.forType(saved.type) ?: throw SerializationException("unknown route type ${saved.type}")
        return Entry(
            id = saved.id,
            route = RouteJson.decodeFromString(entry.routeSerializer, saved.route),
            transition = saved.transition,
            request = saved.request,
        )
    }
}

@Serializable
private data class SavedEntry(
    val id: String,
    val type: String,
    val route: String,
    val transition: NavTransition,
    val request: ResultRequest? = null,
)

/** Tolerates fields removed from routes between app versions. */
private val RouteJson = Json { ignoreUnknownKeys = true }

private const val ENTRY_ID_LENGTH = 12
