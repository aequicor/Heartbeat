package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptConstraints
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiConfiguration
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime.UnsupportedKimiCandidates
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Kimi ACP connector cannot prove the strict isolated contract, so every strict call must be a
 * pre-dispatch refusal. The fake dependencies throw if the backend opens a process, session, discovery,
 * permission handler, history store or configuration writer.
 */
class KimiIsolatedPromptsTest {
    @Test
    fun `strict prompt is refused before any process session or dispatch`() = runTest {
        withBackend { backend, probe ->
            val result = backend.answerIsolated(
                prompt = prompt(),
                source = source,
                context = context,
                deadline = TimeSource.Monotonic.markNow() + 5.seconds,
                beforeDispatch = { probe.calls++ },
            )

            assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.IsolationUnproven), result)
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `expired deadlines and repeated calls never create a session or fall back`() = runTest {
        withBackend { backend, probe ->
            val expired = TimeSource.Monotonic.markNow() - 1.seconds
            repeat(2) { index ->
                val result = backend.answerIsolated(
                    prompt = prompt(),
                    source = source,
                    context = context,
                    deadline = if (index == 0) expired else TimeSource.Monotonic.markNow() + 5.seconds,
                    beforeDispatch = { probe.calls++ },
                )
                assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.IsolationUnproven), result)
            }
            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `cancellation leaves no owned ACP session or process`() = runTest {
        withBackend { backend, probe ->
            val owner = Job(coroutineContext.job)
            val call = CoroutineScope(coroutineContext + owner).async(start = CoroutineStart.LAZY) {
                backend.answerIsolated(
                    prompt = prompt(),
                    source = source,
                    context = context,
                    deadline = TimeSource.Monotonic.markNow() + 5.seconds,
                    beforeDispatch = { probe.calls++ },
                )
            }
            call.cancel()
            call.join()
            owner.cancel()

            assertEquals(0, probe.calls)
        }
    }

    @Test
    fun `strict prompts are not accepted without constraints`() = runTest {
        withBackend { backend, probe ->
            val result = backend.answerIsolated(
                prompt = IsolatedPrompt(target, "READY"),
                source = source,
                context = context,
                deadline = TimeSource.Monotonic.markNow() + 5.seconds,
                beforeDispatch = { probe.calls++ },
            )

            assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.IsolationUnproven), result)
            assertEquals(0, probe.calls)
        }
    }

    private suspend fun withBackend(block: suspend (JvmKimiBackend, DispatchProbe) -> Unit) {
        val client = HttpClient(MockEngine { error("Strict Kimi isolation must not use HTTP") })
        try {
            val setup = KimiRuntimeSetup(NoManagedKimiRuntime, TestKimiDispatchers, resourcesDirectory = null)
            val backend = JvmKimiBackend(
                transports = unused(),
                clients = unused(),
                workspaces = unused(),
                dispatchers = TestKimiDispatchers,
                toggles = unused(),
                tools = unused(),
                bridge = unused(),
                resources = unused(),
                localApi = JvmKimiLocalApi(client, TestKimiDispatchers, setup, launches = { LaunchContext() }),
                setup = setup,
                compatibilityProbe = KimiCompatibilityProbe(setup, unused(), unused(), TestKimiDispatchers),
                launches = EngineLaunchConfig.Default,
                profile = unused(),
                configuration = KimiConfiguration(),
                candidates = UnsupportedKimiCandidates,
            )
            block(backend, DispatchProbe())
        } finally {
            client.close()
        }
    }

    private fun prompt(): IsolatedPrompt = IsolatedPrompt(
        target,
        "READY",
        maxAnswerChars = 256,
        constraints = IsolatedPromptConstraints(source.withoutLabel(), 32),
    )

    private val source = AuthSource.CliLogin(
        AuthSourceInfo(KimiEngine.AuthSource, "Kimi", AuthRevision.Known("r1")),
        AuthScope(ProviderId("kimi"), EndpointOrigin("https://api.kimi.com")),
        KimiEngine.AuthOwner,
        KimiEngine.AuthLocation,
    )
    private val target = EngineTarget(KimiEngine.Id, EngineBindingId("binding"), ModelId("kimi-for-coding"))
    private val context = EngineContext(KimiEngine.Id, EngineBindingId("binding"), model = target.model)

    private class DispatchProbe {
        var calls: Int = 0
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, _, _ -> error("Strict Kimi isolation must not open a process, ACP session or tool") } as T
}
