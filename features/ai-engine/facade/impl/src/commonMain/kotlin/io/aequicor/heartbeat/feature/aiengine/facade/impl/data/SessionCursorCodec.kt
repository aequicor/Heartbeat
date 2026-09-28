package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCursor
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.DecodedCursor
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.IndexPosition
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionCursors
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * [SessionCursors] as URL-safe Base64 JSON. The scope field is a fingerprint of the profile and the query, so a
 * cursor never carries the raw profile id or search text and cannot be replayed with another query.
 */
class SessionCursorCodec(private val profile: String) : SessionCursors {
    private val log = Log.tag("SessionCursorCodec")

    override fun encode(query: SessionQuery, revision: Long, position: IndexPosition): SessionCursor {
        val payload = CursorPayload(scopeOf(query), revision, position.sortKey, position.refKey)
        return SessionCursor(Base64.UrlSafe.encode(CursorJson.encodeToString(payload).encodeToByteArray()))
    }

    override fun decode(cursor: SessionCursor, query: SessionQuery): DecodedCursor? {
        val payload = parse(cursor) ?: return null
        if (payload.scope != scopeOf(query)) {
            log.w { "session cursor of another profile or query" }
            return null
        }
        return DecodedCursor(payload.revision, IndexPosition(payload.sortKey, payload.refKey))
    }

    private fun parse(cursor: SessionCursor): CursorPayload? = try {
        CursorJson.decodeFromString<CursorPayload>(Base64.UrlSafe.decode(cursor.value).decodeToString())
    } catch (e: SerializationException) {
        log.w(e) { "malformed session cursor" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "malformed session cursor encoding" }
        null
    }

    private fun scopeOf(query: SessionQuery): String {
        val canonical = query.copy(
            engines = query.engines.sortedBy { it.value }.toSet(),
            sources = query.sources.sortedBy { it.value }.toSet(),
        )
        return fingerprint(profile + "\u0000" + CursorJson.encodeToString(SessionQuery.serializer(), canonical))
    }

    /** 64-bit FNV-1a over UTF-8: stable across platforms, not reversible to the profile id or search text. */
    private fun fingerprint(text: String): String {
        var hash = FNV_OFFSET
        text.encodeToByteArray().forEach { byte ->
            hash = (hash xor (byte.toULong() and BYTE_MASK)) * FNV_PRIME
        }
        return hash.toString(radix = 16)
    }

    @Serializable
    private data class CursorPayload(val scope: String, val revision: Long, val sortKey: Long, val refKey: String)

    private companion object {
        val CursorJson = Json { encodeDefaults = true }
        const val FNV_OFFSET = 0xcbf29ce484222325uL
        const val FNV_PRIME = 0x100000001b3uL
        const val BYTE_MASK = 0xffuL
    }
}
