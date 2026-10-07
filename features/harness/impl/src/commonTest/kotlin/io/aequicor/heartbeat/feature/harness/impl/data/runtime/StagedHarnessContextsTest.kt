package io.aequicor.heartbeat.feature.harness.impl.data.runtime

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.schedulerTestFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessEvaluationContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StagedHarnessContextsTest {
    @Test
    fun `workflow activation requires one definition without executing its body`() {
        val context = HarnessWorkflowContext()
        val definition: WorkflowDefinition = { error("Body must not execute during activation") }
        assertFalse(context.isReadyForPublication)
        context.evaluation.registration.register(definition)
        assertTrue(context.isReadyForPublication)
        assertSame(definition, context.definition)
        assertFailsWith<IllegalStateException> { context.evaluation.registration.register { JsonNull } }
        context.close()
        context.close()
        assertNull(context.definition)
        assertFalse(context.isReadyForPublication)
        assertFailsWith<IllegalStateException> { context.evaluation.registration.register { JsonNull } }
    }

    @Test
    fun `script service without its owner rejects access instead of pretending to register`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val access = object : HarnessInstanceAccess {
            override val scope = backgroundScope
            override val dispatcher = dispatcher
            override val isActive = false
            override suspend fun awaitPublication() = false
        }
        val origins = object : HarnessCallOrigins {
            override fun current() = HarnessCallOrigin()
            override fun context(origin: HarnessCallOrigin) = HarnessOriginContext(origin)
        }
        val schedulers = schedulerTestFactory(origins, dispatcher) { error("Inactive instance cannot reach runtime") }
        val context = StagedHarnessContexts(
            origins,
            schedulers,
        ).create(HarnessActivationRequest(harness, code, 1), access)
        val script = assertIs<HarnessEvaluationContext.Script>(context.evaluation).scope
        assertSame(backgroundScope, script.scope)
        assertFailsWith<IllegalStateException> {
            script.events.on(
                HarnessEvent::class,
            ) {}
        }
        assertFailsWith<IllegalStateException> { script.hooks.beforePrompt { _, _ -> null } }
        assertFailsWith<UnsupportedOperationException> { script.sessions }
        assertFailsWith<IllegalStateException> { script.scheduler.every(30.seconds) {} }
        assertFailsWith<IllegalStateException> { script.agent.instructions { "" } }
        assertFailsWith<UnsupportedOperationException> { script.prompts }
        assertFailsWith<UnsupportedOperationException> { script.workflows }
        context.close()
    }
}
