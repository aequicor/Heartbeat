package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineConnectionsRouteTest {
    @Test
    fun `legacy route keeps the full settings space`() {
        assertEquals(EngineConnectionsRoute(), Json.decodeFromString<EngineConnectionsRoute>("{}"))
        assertEquals(
            EngineConnectionsRoute(isEmbedded = true),
            Json.decodeFromString<EngineConnectionsRoute>("""{"isEmbedded":true}"""),
        )
    }

    @Test
    fun `fixed engine survives saved route restoration`() {
        val route = EngineConnectionsRoute(isEmbedded = true, engine = EngineId("koog"))
        val encoded = Json.encodeToString(EngineConnectionsRoute.serializer(), route)
        assertEquals(route, Json.decodeFromString<EngineConnectionsRoute>(encoded))
    }
}
