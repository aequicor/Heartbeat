package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceResolver
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchThumbnailEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchThumbnailsTest {
    @Test
    fun `document snippet is bounded and disposed rows release it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val previews = ResearchThumbnails(
            backgroundScope,
            ResourceResolver { ResolvedResource("source.txt", "text/plain", "x".repeat(4096).encodeToByteArray()) },
            provider,
            ResearchThumbnailEncoder { it },
        )
        previews.load("row", ResourceRef("attachment:source", "text/plain"))
        runCurrent()
        assertEquals(160, previews.state.value.getValue("row").documentSnippet?.length)
        previews.release("row")
        assertTrue(previews.state.value.isEmpty())
    }

    @Test
    fun `closing a row cancels an unfinished read and cannot restore its preview`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val result = CompletableDeferred<ResolvedResource?>()
        val previews = ResearchThumbnails(
            backgroundScope,
            ResourceResolver { result.await() },
            provider,
            ResearchThumbnailEncoder { it },
        )
        previews.load("row", ResourceRef("attachment:source", "text/plain"))
        runCurrent()
        previews.release("row")
        result.complete(ResolvedResource("source.txt", "text/plain", "secret".encodeToByteArray()))
        runCurrent()
        assertTrue(previews.state.value.isEmpty())
    }
}
