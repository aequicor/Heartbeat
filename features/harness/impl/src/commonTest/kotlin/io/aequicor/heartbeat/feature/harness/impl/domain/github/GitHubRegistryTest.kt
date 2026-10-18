package io.aequicor.heartbeat.feature.harness.impl.domain.github

import io.aequicor.heartbeat.feature.harness.api.github.GitHubChange
import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ProcessId
import io.aequicor.heartbeat.feature.harness.api.process.ResultRef
import io.aequicor.heartbeat.feature.harness.api.process.WriteKey
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessDataTestSecrets
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestEnv
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubRegistryTest {
    @Test
    fun `only explicit never started receipts may be closed and closed producers cannot dispatch`() = runTest {
        val env = ProcessesTestEnv(this)
        val registry = GitHubRegistry(env.data)
        val candidate = GitHubOperation(
            ProcessId("work"),
            "incarnation",
            0,
            WriteKey("comment"),
            GitHubRepository("owner", "repo"),
            GitHubChange.Comment(1, "body"),
        )
        registry.reserve(candidate)
        assertNull(registry.closeUnsent(candidate))
        val pending = candidate.copy(key = WriteKey("pending"), marker = "pending", hasSendStarted = false)
        registry.reserve(pending)
        val closed = checkNotNull(registry.closeUnsent(pending))
        assertTrue(closed.refusal != null)
        assertFalse(registry.markSendStarted(pending))
        val sending = pending.copy(key = WriteKey("sending"), marker = "sending")
        registry.reserve(sending)
        assertTrue(registry.markSendStarted(sending))
        assertFalse(registry.markSendStarted(sending))
        assertNull(registry.closeUnsent(sending))
        assertEquals(true, registry.find(sending.process, sending.incarnation, sending.key)?.hasSendStarted)
    }

    @Test
    fun `concurrent reservations retain one permanent identity across profile restart`() = runTest {
        val secrets = HarnessDataTestSecrets()
        val env = ProcessesTestEnv(this, secrets)
        val candidate = GitHubOperation(
            ProcessId("work"),
            "incarnation",
            0,
            WriteKey("comment"),
            GitHubRepository("owner", "repo"),
            GitHubChange.Comment(1, "body"),
        )
        val first = async { GitHubRegistry(env.data).reserve(candidate) }
        val second = async { GitHubRegistry(env.data).reserve(candidate.copy(marker = "other")) }
        assertIs<GitHubReservation.Reserved>(first.await())
        assertEquals(GitHubReservation.Existing(candidate), second.await())
        GitHubRegistry(env.data).settle(candidate, result = ResultRef("github_comment", "7"))
        val restarted = ProcessesTestEnv(this, secrets, restartedAt = 100)
        assertEquals(
            candidate.copy(result = ResultRef("github_comment", "7")),
            GitHubRegistry(restarted.data).find(candidate.process, candidate.incarnation, candidate.key),
        )
    }

    @Test
    fun `future registry version is not silently reset by a new reservation`() = runTest {
        val env = ProcessesTestEnv(this)
        val future = buildJsonObject {
            put("version", 100)
            put("private", "future data")
        }
        env.data.update("hb_github") { future }
        val candidate = GitHubOperation(
            ProcessId("work"),
            "incarnation",
            0,
            WriteKey("comment"),
            GitHubRepository("owner", "repo"),
            GitHubChange.Comment(1, "body"),
        )
        assertFailsWith<IllegalStateException> { GitHubRegistry(env.data).reserve(candidate) }
        assertEquals(future, env.data.read("hb_github").value)
    }

    @Test
    fun `missing stored operation marker fails closed instead of generating a new identity`() = runTest {
        val env = ProcessesTestEnv(this)
        val operation = GitHubOperation(
            ProcessId("work"),
            "incarnation",
            0,
            WriteKey("comment"),
            GitHubRepository("owner", "repo"),
            GitHubChange.Comment(1, "body"),
        )
        val missingMarker = JsonObject(Json.encodeToJsonElement(operation).jsonObject - "marker")
        val corrupt = buildJsonObject {
            put("version", 1)
            put("operations", JsonArray(listOf(missingMarker)))
        }
        env.data.update("hb_github") { corrupt }
        assertFailsWith<IllegalStateException> {
            GitHubRegistry(env.data).find(operation.process, operation.incarnation, operation.key)
        }
        assertEquals(corrupt, env.data.read("hb_github").value)
    }
}
