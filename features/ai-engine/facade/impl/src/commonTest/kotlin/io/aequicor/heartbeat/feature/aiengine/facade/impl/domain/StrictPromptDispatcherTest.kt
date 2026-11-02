package io.aequicor.heartbeat.feature.aiengine.facade.impl.domain

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.withoutLabel
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptConstraints
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPromptFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.StrictPromptDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class StrictPromptDispatcherTest {
    @Test
    fun `revoking the host run during a canonical lookup prevents dispatch or a usable reply`() = runTest {
        for (isBeforeDispatch in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var isCurrent = true
            var requests = 0
            var prepareLookup: () -> Unit = {}
            val native = FakeEngineFactory()
            val factory = object : EngineFactory by native {
                override suspend fun answerIsolated(
                    prompt: IsolatedPrompt,
                    source: AuthSource,
                    context: EngineContext,
                    deadline: TimeMark,
                    beforeDispatch: suspend () -> Unit,
                ): IsolatedAnswer {
                    if (isBeforeDispatch) prepareLookup()
                    beforeDispatch()
                    requests++
                    if (!isBeforeDispatch) prepareLookup()
                    return IsolatedAnswer.Answered("must not be sent")
                }
            }
            val fixture = RouteFixture(this, native, registration(factory))
            prepareLookup = {
                fixture.store.beforeLoad = {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val prompt = prompt(fixture).let {
                it.copy(constraints = requireNotNull(it.constraints).copy(isCurrent = { isCurrent }))
            }
            val result = async { dispatcher(fixture).answer(prompt, TimeSource.Monotonic.markNow() + 5.seconds) }
            entered.await()
            isCurrent = false
            release.complete(Unit)
            assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.SourceChanged), result.await())
            assertEquals(if (isBeforeDispatch) 0 else 1, requests)
        }
    }

    @Test
    fun `host run revocation before setup before dispatch or after reply prevents a usable answer`() = runTest {
        for (phase in 0..2) {
            var isCurrent = phase != 0
            var constructions = 0
            var requests = 0
            val native = FakeEngineFactory()
            val factory = object : EngineFactory by native {
                override suspend fun answerIsolated(
                    prompt: IsolatedPrompt,
                    source: AuthSource,
                    context: EngineContext,
                    deadline: TimeMark,
                    beforeDispatch: suspend () -> Unit,
                ): IsolatedAnswer {
                    constructions++
                    if (phase == 1) isCurrent = false
                    beforeDispatch()
                    requests++
                    isCurrent = false
                    return IsolatedAnswer.Answered("revoked run answer")
                }
            }
            val fixture = RouteFixture(this, native, registration(factory))
            val prompt = prompt(fixture).let {
                it.copy(constraints = requireNotNull(it.constraints).copy(isCurrent = { isCurrent }))
            }
            val result = dispatcher(fixture).answer(prompt, TimeSource.Monotonic.markNow() + 5.seconds)
            assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.SourceChanged), result)
            assertEquals(if (phase == 0) 0 else 1, constructions)
            assertEquals(if (phase == 2) 1 else 0, requests)
        }
    }

    @Test
    fun `host authorization consumes the original deadline before adapter construction`() = runTest {
        val fixture = RouteFixture(this)
        val time = TestTimeSource()
        val prompt = prompt(fixture).let {
            it.copy(
                constraints = requireNotNull(it.constraints).copy(isCurrent = {
                    time += 6.seconds
                    true
                }),
            )
        }
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout),
            dispatcher(fixture).answer(prompt, time.markNow() + 5.seconds),
        )
        assertTrue(fixture.factory.createdRuntimes.isEmpty())
    }

    @Test
    fun `unsupported adapter refuses without binding discovery preparation or runtime`() = runTest {
        val fixture = RouteFixture(this)
        val result = dispatcher(fixture).answer(prompt(fixture), TimeSource.Monotonic.markNow() + 5.seconds)
        assertEquals(IsolatedAnswer.Unavailable(IsolatedPromptFailure.IsolationUnproven), result)
        assertTrue(fixture.factory.routes.isEmpty())
        assertEquals(0, fixture.factory.modelDiscoveries)
        assertEquals(0, fixture.factory.preparations)
        assertTrue(fixture.factory.createdRuntimes.isEmpty())
    }

    @Test
    fun `same id and revision with different endpoint or secret refuses before adapter`() = runTest {
        var calls = 0
        val native = FakeEngineFactory()
        val factory = object : EngineFactory by native {
            override suspend fun answerIsolated(
                prompt: IsolatedPrompt,
                source: AuthSource,
                context: EngineContext,
                deadline: TimeMark,
                beforeDispatch: suspend () -> Unit,
            ): IsolatedAnswer {
                calls++
                return IsolatedAnswer.Answered("READY")
            }
        }
        val fixture = RouteFixture(this, native, registration(factory))
        val source = fixture.source as AuthSource.ManagedKey
        val variants = listOf(
            source.copy(secret = AuthSecretId("other")),
            source.copy(scope = source.scope.copy(origin = EndpointOrigin("https://other.example.com"))),
            source.copy(scope = source.scope.copy(basePath = "/v2")),
        )
        for (variant in variants) {
            fixture.sources.remove(source.info.id)
            fixture.sources.add(variant)
            assertEquals(
                IsolatedAnswer.Unavailable(IsolatedPromptFailure.SourceChanged),
                dispatcher(fixture).answer(prompt(fixture), TimeSource.Monotonic.markNow() + 5.seconds),
            )
        }
        assertEquals(0, calls)
        assertTrue(native.routes.isEmpty())
    }

    @Test
    fun `source change during request discards completed answer`() = runTest {
        val native = FakeEngineFactory()
        var change: () -> Unit = {}
        val factory = object : EngineFactory by native {
            override suspend fun answerIsolated(
                prompt: IsolatedPrompt,
                source: AuthSource,
                context: EngineContext,
                deadline: TimeMark,
                beforeDispatch: suspend () -> Unit,
            ): IsolatedAnswer {
                assertNull(context.workspace)
                assertEquals(prompt.target.model, context.model)
                change()
                return IsolatedAnswer.Answered("stale")
            }
        }
        val fixture = RouteFixture(this, native, registration(factory))
        change = {
            val source = fixture.source as AuthSource.ManagedKey
            fixture.sources.remove(source.info.id)
            fixture.sources.add(source.copy(secret = AuthSecretId("replacement")))
        }
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.SourceChanged),
            dispatcher(fixture).answer(prompt(fixture), TimeSource.Monotonic.markNow() + 5.seconds),
        )
    }

    @Test
    fun `label changes preserve exact context but oversized strict answer is refused`() = runTest {
        val native = FakeEngineFactory()
        val factory = object : EngineFactory by native {
            override suspend fun answerIsolated(
                prompt: IsolatedPrompt,
                source: AuthSource,
                context: EngineContext,
                deadline: TimeMark,
                beforeDispatch: suspend () -> Unit,
            ): IsolatedAnswer = IsolatedAnswer.Answered("READY")
        }
        val fixture = RouteFixture(this, native, registration(factory))
        val source = fixture.source as AuthSource.ManagedKey
        fixture.sources.remove(source.info.id)
        fixture.sources.add(source.copy(info = source.info.copy(label = "renamed")))
        assertEquals(
            IsolatedAnswer.Answered("READY"),
            dispatcher(fixture).answer(prompt(fixture), TimeSource.Monotonic.markNow() + 5.seconds),
        )
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.ResponseTooLarge),
            dispatcher(
                fixture,
            ).answer(prompt(fixture).copy(maxAnswerChars = 4), TimeSource.Monotonic.markNow() + 5.seconds),
        )
        assertTrue(native.routes.isEmpty())
    }

    @Test
    fun `route validation cannot restart the deadline before provider dispatch`() = runTest {
        val clock = TestTimeSource()
        val deadline = clock.markNow() + 5.seconds
        val native = FakeEngineFactory()
        var dispatched = false
        val factory = object : EngineFactory by native {
            override suspend fun answerIsolated(
                prompt: IsolatedPrompt,
                source: AuthSource,
                context: EngineContext,
                deadline: TimeMark,
                beforeDispatch: suspend () -> Unit,
            ): IsolatedAnswer {
                dispatched = true
                return IsolatedAnswer.Answered("late")
            }
        }
        val fixture = RouteFixture(this, native, registration(factory))
        native.accepts = { _, _ ->
            clock += 6.seconds
            true
        }
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.Timeout),
            dispatcher(fixture).answer(prompt(fixture), deadline),
        )
        assertTrue(!dispatched)
    }

    @Test
    fun `canonical binding disable blocks dispatch while the adapter cache stays enabled`() = runTest {
        val native = FakeEngineFactory()
        var disable: () -> Unit = {}
        var requests = 0
        val factory = object : EngineFactory by native {
            override suspend fun answerIsolated(
                prompt: IsolatedPrompt,
                source: AuthSource,
                context: EngineContext,
                deadline: TimeMark,
                beforeDispatch: suspend () -> Unit,
            ): IsolatedAnswer {
                disable()
                beforeDispatch()
                requests++
                return IsolatedAnswer.Answered("stale")
            }
        }
        val fixture = RouteFixture(this, native, registration(factory))
        native.routes[fixture.binding.id] = fixture.source
        disable = { fixture.store.bindings.value = listOf(fixture.binding.copy(isEnabled = false)) }
        assertFailsWith<EngineException> {
            dispatcher(fixture).answer(prompt(fixture), TimeSource.Monotonic.markNow() + 5.seconds)
        }
        assertEquals(0, requests)
        assertEquals(fixture.source, native.routes[fixture.binding.id])
    }

    @Test
    fun `canonical source replacement during adapter setup refuses before model dispatch`() = runTest {
        val native = FakeEngineFactory()
        var replace: () -> Unit = {}
        var requests = 0
        val factory = object : EngineFactory by native {
            override suspend fun answerIsolated(
                prompt: IsolatedPrompt,
                source: AuthSource,
                context: EngineContext,
                deadline: TimeMark,
                beforeDispatch: suspend () -> Unit,
            ): IsolatedAnswer {
                replace()
                beforeDispatch()
                requests++
                return IsolatedAnswer.Answered("stale")
            }
        }
        val fixture = RouteFixture(this, native, registration(factory))
        replace = {
            val source = fixture.source as AuthSource.ManagedKey
            fixture.sources.remove(source.info.id)
            fixture.sources.add(source.copy(secret = AuthSecretId("replacement")))
        }
        assertEquals(
            IsolatedAnswer.Unavailable(IsolatedPromptFailure.SourceChanged),
            dispatcher(fixture).answer(prompt(fixture), TimeSource.Monotonic.markNow() + 5.seconds),
        )
        assertEquals(0, requests)
    }

    private fun prompt(fixture: RouteFixture) = IsolatedPrompt(
        fixture.target,
        "READY",
        constraints = IsolatedPromptConstraints(fixture.source.withoutLabel(), 32),
    )

    private fun TestScope.dispatcher(fixture: RouteFixture): StrictPromptDispatcher {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        return StrictPromptDispatcher(fixture.routes, dispatchers)
    }
}
