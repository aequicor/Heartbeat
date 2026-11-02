package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ModelCheckReportTest {
    private val generation = ModelCheckGeneration("11111111-1111-1111-1111-111111111111")
    private val another = ModelCheckGeneration("22222222-2222-2222-2222-222222222222")
    private val source = AuthSource.NoAuth(
        AuthSourceInfo(AuthSourceId("source"), "", AuthRevision.Known("revision")),
        AuthScope(ProviderId("provider"), EndpointOrigin("https://private.example")),
    )
    private val revision = ModelCheckRevision(
        EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model")),
        generation,
        source,
        ModelCheckRevision.CURRENT_DEFINITION,
    )
    private val passed = ModelCheckAspect("probe-canary", "expected-canary", "answer-canary", ModelCheckOutcome.Passed)
    private val report = ModelCheckReport(revision, another, Instant.fromEpochSeconds(0), passed, passed)

    @Test
    fun `two successes discard diagnostic text while a warning retains both independent aspects`() {
        val success = ModelCheckReceipt.from(report)
        assertFalse(report.hasWarning)
        assertEquals(true, success.isPassed)
        assertNull(success.report)
        val incomplete = passed.copy(
            answer = null,
            outcome = ModelCheckOutcome.Incomplete(ModelCheckIncomplete.Timeout),
        )
        val warning = ModelCheckReceipt.from(report.copy(accuracy = incomplete))
        assertEquals(false, warning.isPassed)
        assertEquals(passed, warning.report?.response)
        assertEquals(incomplete, warning.report?.accuracy)
    }

    @Test
    fun `unknown authentication never has an identity without a local generation`() {
        val unknown = source.copy(info = source.info.copy(revision = AuthRevision.Unknown))
        assertFailsWith<IllegalArgumentException> { revision.copy(source = unknown) }
        val beforeReload = revision.copy(source = unknown, localAuth = generation)
        assertNotEquals(beforeReload, beforeReload.copy(localAuth = another))
        assertNotEquals(revision, revision.copy(definition = ModelCheckRevision.CURRENT_DEFINITION + 1))
        assertNotEquals(revision, revision.copy(source = source.copy(scope = source.scope.copy(basePath = "/api"))))
        assertFailsWith<IllegalArgumentException> {
            revision.copy(source = source.copy(info = source.info.copy(label = "account-label-canary")))
        }
    }

    @Test
    fun `receipt cannot claim success with a diagnostic report or mismatch its identity`() {
        assertFailsWith<IllegalArgumentException> {
            ModelCheckReceipt(revision, another, report.checkedAt, true, report)
        }
        assertFailsWith<IllegalArgumentException> {
            ModelCheckReceipt(revision, another, report.checkedAt, false, null)
        }
        val failed = report.copy(
            accuracy = passed.copy(outcome = ModelCheckOutcome.Failed(ModelCheckProblem.UnexpectedAnswer)),
        )
        assertFailsWith<IllegalArgumentException> {
            ModelCheckReceipt(revision, generation, report.checkedAt, false, failed)
        }
    }

    @Test
    fun `text and identity bounds prevent unbounded records or fabricated completed answers`() {
        assertFailsWith<IllegalArgumentException> { generation.copy(value = "not-a-generation") }
        assertFailsWith<IllegalArgumentException> {
            passed.copy(probe = "x".repeat(ModelCheckAspect.MAX_PROBE_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            passed.copy(expected = "x".repeat(ModelCheckAspect.MAX_EXPECTED_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            passed.copy(answer = "x".repeat(ModelCheckAspect.MAX_ANSWER_CHARS + 1))
        }
        assertFailsWith<IllegalArgumentException> { passed.copy(answer = "   ") }
        assertFailsWith<IllegalArgumentException> {
            passed.copy(answer = null, outcome = ModelCheckOutcome.Failed(ModelCheckProblem.EmptyAnswer))
        }
        assertFailsWith<IllegalArgumentException> {
            revision.copy(target = revision.target.copy(model = ModelId("m".repeat(1025))))
        }
    }

    @Test
    fun `diagnostic string representations never contain routes prompts answers or generations`() {
        val printed = listOf(generation, revision, passed, report, ModelCheckReceipt.from(report)).joinToString()
        listOf("private.example", "probe-canary", "expected-canary", "answer-canary", generation.value).forEach {
            assertFalse(printed.contains(it), printed)
        }
    }
}
