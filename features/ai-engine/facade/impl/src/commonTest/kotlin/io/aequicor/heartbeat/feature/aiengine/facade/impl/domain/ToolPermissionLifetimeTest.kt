package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolPermissionLifetimeTest {
    @Test
    fun `failed first mode read disposes the native lifetime handler`() = runTest {
        val lifetime = ObservedLifetime()
        val tools = DefaultAgentTools(emptySet(), settings = settings(flow { error("Unreadable setting") }))
        assertFailsWith<IllegalStateException> {
            tools.execute(context(lifetime), "read", JsonObject(emptyMap()))
        }
        assertEquals(0, lifetime.handlers)
        lifetime.cancel()
    }

    @Test
    fun `cancelled first mode read disposes the native lifetime handler`() = runTest {
        val lifetime = ObservedLifetime()
        val entered = CompletableDeferred<Unit>()
        val tools = DefaultAgentTools(
            emptySet(),
            settings = settings(
                flow {
                    entered.complete(Unit)
                    awaitCancellation()
                },
            ),
        )
        val call = async { tools.execute(context(lifetime), "read", JsonObject(emptyMap())) }
        entered.await()
        assertEquals(1, lifetime.handlers)
        call.cancelAndJoin()
        assertEquals(0, lifetime.handlers)
        lifetime.cancel()
    }

    private fun settings(values: Flow<SecurityMode>) = object : AgentSecuritySettings {
        override val modes = values
        override suspend fun load(): SecurityMode? = error("Unused")
        override suspend fun save(mode: SecurityMode): Unit = error("Unused")
    }

    private fun context(lifetime: Job) = AgentToolContext(
        SessionRef(EngineId("test"), SessionSourceId("local"), "session"),
        null,
        TurnId("turn"),
        lifetime = lifetime,
    )

    private class ObservedLifetime(private val delegate: Job = Job()) : Job by delegate {
        var handlers = 0
            private set

        override fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle {
            handlers++
            val registration = delegate.invokeOnCompletion(handler)
            return object : DisposableHandle {
                override fun dispose() {
                    registration.dispose()
                    handlers--
                }
            }
        }
    }
}
