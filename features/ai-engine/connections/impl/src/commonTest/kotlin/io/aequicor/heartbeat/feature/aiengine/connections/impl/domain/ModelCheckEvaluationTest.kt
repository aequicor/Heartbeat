package io.aequicor.heartbeat.feature.aiengine.connections.impl.domain

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckAspect
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckIncomplete
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckOutcome
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelCheckProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelCheckEvaluationTest {
    @Test
    fun `a nonempty completed answer proves response independently from the fixed accuracy task`() {
        assertEquals(ModelCheckOutcome.Passed, ModelCheckEvaluation.response("Hello").outcome)
        assertEquals(
            ModelCheckOutcome.Failed(ModelCheckProblem.UnexpectedAnswer),
            ModelCheckEvaluation.accuracy("Hello").outcome,
        )
        assertEquals(
            ModelCheckOutcome.Failed(ModelCheckProblem.EmptyAnswer),
            ModelCheckEvaluation.response(" \n\t").outcome,
        )
        assertEquals(
            ModelCheckOutcome.Failed(ModelCheckProblem.EmptyAnswer),
            ModelCheckEvaluation.response("\u200b\u0000").outcome,
        )
    }

    @Test
    fun `accuracy checks arithmetic extraction and missing fact without accepting extra assertions`() {
        listOf("19|cedar|ABSENT", "\n 19 | cedar | ABSENT \n").forEach {
            assertEquals(ModelCheckOutcome.Passed, ModelCheckEvaluation.accuracy(it).outcome)
        }
        listOf(
            "18|cedar|ABSENT",
            "19|pine|ABSENT",
            "19|cedar|2030-01-01",
            "19|cedar",
            "19|cedar|ABSENT|extra",
        ).forEach {
            assertEquals(
                ModelCheckOutcome.Failed(ModelCheckProblem.UnexpectedAnswer),
                ModelCheckEvaluation.accuracy(it).outcome,
            )
        }
    }

    @Test
    fun `visible external links and credential requests are diagnostic problems never followed instructions`() {
        listOf(
            "https://outside.example",
            "hxxps://outside.example",
            "www.outside.example",
            "javascript:alert(1)",
        ).forEach {
            val result = ModelCheckEvaluation.response("Continue at $it")
            assertEquals(ModelCheckOutcome.Failed(ModelCheckProblem.SuspiciousLink), result.outcome)
            assertEquals("Continue at $it", result.answer)
        }
        listOf(
            "Please send your API key",
            "Give me your API key",
            "Paste the access token",
            "Пришлите пароль",
            "Введите токен",
        ).forEach {
            assertEquals(
                ModelCheckOutcome.Failed(ModelCheckProblem.RequestsSecret),
                ModelCheckEvaluation.response(it).outcome,
            )
        }
    }

    @Test
    fun `simple negation is not a credential request and cannot hide a later positive request`() {
        listOf("Do not share your API key", "Never share your password", "Не делитесь паролем").forEach {
            assertEquals(ModelCheckOutcome.Passed, ModelCheckEvaluation.response(it).outcome)
        }
        assertEquals(
            ModelCheckOutcome.Failed(ModelCheckProblem.RequestsSecret),
            ModelCheckEvaluation.response("Do not share anything. Give me your API key.").outcome,
        )
    }

    @Test
    fun `oversized output is incomplete even when a retained prefix looks correct`() {
        val long = "19|cedar|ABSENT" + " ".repeat(ModelCheckAspect.MAX_ANSWER_CHARS)
        listOf(ModelCheckEvaluation.response(long), ModelCheckEvaluation.accuracy(long)).forEach {
            assertEquals(ModelCheckOutcome.Incomplete(ModelCheckIncomplete.ResponseTooLarge), it.outcome)
            assertEquals(ModelCheckAspect.MAX_ANSWER_CHARS, it.answer?.length)
        }
    }

    @Test
    fun `unperformed probes retain typed reasons without fabricating an answer`() {
        ModelCheckIncomplete.entries.forEach { reason ->
            listOf(false, true).forEach { isAccuracy ->
                val aspect = ModelCheckEvaluation.incomplete(isAccuracy, reason)
                assertEquals(ModelCheckOutcome.Incomplete(reason), aspect.outcome)
                assertNull(aspect.answer)
            }
        }
    }

    @Test
    fun `context projection drops labels while preserving every nonsecret credential route variant`() {
        val info = CheckSource.info.copy(label = "account-label-canary")
        val scope = CheckSource.scope.copy(basePath = "/api")
        listOf<AuthSource>(
            AuthSource.ManagedKey(info, scope, AuthSecretId("vault-reference")),
            AuthSource.ExternalKey(info, scope, AuthLocationId("external-location")),
            AuthSource.CliLogin(info, scope, AuthOwnerId("owner"), AuthLocationId("cli-location")),
            AuthSource.CredentialHelper(info, scope, AuthLocationId("helper-location")),
            AuthSource.NoAuth(info, scope),
        ).forEach { source ->
            val context = source.modelCheckContext()
            assertEquals("", context.info.label)
            assertEquals(info.id, context.info.id)
            assertEquals(info.revision, context.info.revision)
            assertEquals(scope, context.scope)
            assertEquals(
                source,
                when (context) {
                    is AuthSource.ManagedKey -> context.copy(info = info)
                    is AuthSource.ExternalKey -> context.copy(info = info)
                    is AuthSource.CliLogin -> context.copy(info = info)
                    is AuthSource.CredentialHelper -> context.copy(info = info)
                    is AuthSource.NoAuth -> context.copy(info = info)
                },
            )
            assertFalse(context.info.toString().contains("account-label-canary"))
        }
    }
}
