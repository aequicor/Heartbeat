package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PiBuiltinCatalogTest {
    @Test
    fun `both empty and populated catalog responses are cached`() = runTest {
        val model = JsonObject(mapOf("id" to JsonPrimitive("model")))
        listOf(emptyList(), listOf(model)).forEach { response ->
            var calls = 0
            val catalog = PiBuiltinCatalog { _, _ ->
                calls++
                response
            }
            repeat(3) { assertEquals(response, catalog.models(executable, root)) }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `failed probes are attempted once for the profile lifetime`() = runTest {
        val failures = listOf(
            IOException("unavailable executable"),
            SecurityException("denied"),
            EngineException(EngineFailure.Transport(TransportFailureReason.Timeout)),
        )
        failures.forEach { failure ->
            var calls = 0
            val catalog = PiBuiltinCatalog { _, _ ->
                calls++
                throw failure
            }
            repeat(3) { assertEquals(emptyList(), catalog.models(executable, root)) }
            assertEquals(1, calls)
        }
    }

    @Test
    fun `concurrent callers share the same slow empty probe`() = runTest {
        val response = CompletableDeferred<List<JsonObject>>()
        var calls = 0
        val catalog = PiBuiltinCatalog { _, _ ->
            calls++
            response.await()
        }
        val first = async { catalog.models(executable, root) }
        val second = async { catalog.models(executable, root) }
        runCurrent()
        assertEquals(1, calls)
        response.complete(emptyList())
        assertEquals(emptyList(), first.await())
        assertEquals(emptyList(), second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `cancellation does not cache an incomplete probe`() = runTest {
        var calls = 0
        val catalog = PiBuiltinCatalog { _, _ ->
            calls++
            if (calls == 1) throw CancellationException("cancelled caller")
            emptyList()
        }
        assertFailsWith<CancellationException> { catalog.models(executable, root) }
        assertEquals(emptyList(), catalog.models(executable, root))
        assertEquals(2, calls)
    }

    private companion object {
        val executable: Path = Path.of("pi")
        val root: Path = Path.of("runtime")
    }
}
