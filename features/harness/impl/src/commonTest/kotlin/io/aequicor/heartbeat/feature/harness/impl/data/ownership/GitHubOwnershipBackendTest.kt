package io.aequicor.heartbeat.feature.harness.impl.data.ownership

import io.aequicor.heartbeat.feature.harness.api.github.GitHubRepository
import io.aequicor.heartbeat.feature.harness.api.process.ExecutorHostId
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipCommand
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipFence
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipKey
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipPermitKind
import io.aequicor.heartbeat.feature.harness.api.process.OwnershipReduction
import io.aequicor.heartbeat.feature.harness.api.process.ProcessOwnership
import io.aequicor.heartbeat.feature.harness.api.process.reduceOwnership
import io.aequicor.heartbeat.feature.harness.impl.data.services.ProcessesTestClock
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubApi
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipCommit
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipRead
import io.aequicor.heartbeat.feature.harness.impl.domain.ownership.OwnershipUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitHubOwnershipBackendTest {
    private val host = ExecutorHostId("a".repeat(32))
    private val key = OwnershipKey("b".repeat(64))
    private val initial = ProcessOwnership(OwnershipFence(key, host, 1))

    @Test
    fun `git blob hashes include the binary header`() {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", blobHash("hello\n"))
    }

    @Test
    fun `concurrent creates use conditional server writes and exactly one host wins`() = runTest {
        val server = Server()
        server.rendezvous()
        val first = backend(server)
        val second = backend(server)
        val other = initial.copy(fence = initial.fence.copy(host = ExecutorHostId("c".repeat(32))))
        val results = listOf(
            async { first.compareAndSet(key, null, initial) },
            async { second.compareAndSet(key, null, other) },
        ).awaitAll()
        assertEquals(1, results.count { it == OwnershipCommit.Applied })
        assertEquals(1, results.count { it == OwnershipCommit.Conflict })
        assertEquals(1, server.writes)
    }

    @Test
    fun `stale observations cannot acquire a permit after prepare`() = runTest {
        val server = Server()
        val backend = backend(server)
        assertEquals(OwnershipCommit.Applied, backend.compareAndSet(key, null, initial))
        val stale = server.files.values.single()
        val prepare = OwnershipCommand.Prepare(host, initial.fence, "d".repeat(32), ExecutorHostId("e".repeat(32)))
        val prepared = initial.changed(prepare)
        assertEquals(OwnershipCommit.Applied, backend.compareAndSet(key, initial, prepared))
        server.staleRead = stale
        val admitted = initial.changed(
            OwnershipCommand.Admit(host, initial.fence, "f".repeat(32), 1, OwnershipPermitKind.GitHub),
        )
        assertEquals(OwnershipCommit.Conflict, backend.compareAndSet(key, initial, admitted))
        assertEquals(2, server.writes)
    }

    @Test
    fun `lost update response remains unknown and is never automatically resubmitted`() = runTest {
        val server = Server()
        val backend = backend(server)
        server.loseNextResponse = true
        assertEquals(
            OwnershipCommit.Unavailable(OwnershipUnavailable.UnknownCommit),
            backend.compareAndSet(key, null, initial),
        )
        assertEquals(1, server.writes)
        assertEquals(OwnershipRead.Current(initial), backend(server).read(key))
    }

    @Test
    fun `missing access or branch never appears as an unclaimed task`() = runTest {
        val server = Server()
        server.hasAccess = false
        assertEquals(
            OwnershipRead.Unavailable(OwnershipUnavailable.NotConfigured),
            backend(server).read(key),
        )
        assertEquals(0, server.writes)
        server.hasAccess = true
        server.hasBranch = false
        assertEquals(
            OwnershipRead.Unavailable(OwnershipUnavailable.NotConfigured),
            backend(server).read(key),
        )
        assertEquals(0, server.writes)
    }

    @Test
    fun `only approved setup creates the fixed service branch`() = runTest {
        val server = Server()
        server.hasBranch = false
        val backend = backend(server)
        backend.read(key)
        assertEquals(0, server.branchCreates)
        assertTrue(backend.prepare())
        assertEquals(1, server.branchCreates)
        assertTrue(backend.prepare())
        assertEquals(1, server.branchCreates)
    }

    @Test
    fun `service branch cannot be the default even without setup`() = runTest {
        val server = Server()
        val backend = backend(server)
        assertTrue(backend.prepare())
        server.defaultBranch = OWNERSHIP_BRANCH
        assertFalse(backend.prepare())
        assertEquals(
            OwnershipCommit.Unavailable(OwnershipUnavailable.NotConfigured),
            backend.compareAndSet(key, null, initial),
        )
        val content = "{}"
        assertFalse(backend.storeCheckpoint(content.encodeUtf8().sha256().hex(), content))
        assertEquals(0, server.writes)
        assertEquals(0, server.branchCreates)
    }

    @Test
    fun `malformed file or another task key is refused without overwrite`() = runTest {
        val server = Server()
        val backend = backend(server)
        assertEquals(OwnershipCommit.Applied, backend.compareAndSet(key, null, initial))
        val path = server.files.keys.single()
        server.files[path] = "{}"
        assertEquals(OwnershipRead.Unavailable(OwnershipUnavailable.InvalidRecord), backend.read(key))
        assertIs<OwnershipCommit.Unavailable>(backend.compareAndSet(key, null, initial))
        assertEquals(1, server.writes)
    }

    @Test
    fun `checkpoints are immutable digest addressed bytes with verified reads`() = runTest {
        val server = Server()
        val backend = backend(server)
        val content = "{\"version\":1}"
        val digest = content.encodeUtf8().sha256().hex()
        assertTrue(backend.storeCheckpoint(digest, content))
        assertTrue(backend.storeCheckpoint(digest, content))
        assertEquals(1, server.writes)
        assertEquals(content, backend.checkpoint(digest))
        server.files[server.files.keys.single()] = "different"
        assertFalse(backend.storeCheckpoint(digest, content))
        assertEquals(1, server.writes)
    }

    private fun backend(server: Server): GitHubOwnershipBackend =
        GitHubOwnershipBackend(GitHubApi(server, ProcessesTestClock(1000)), GitHubRepository("owner", "repo"))

    private fun ProcessOwnership.changed(command: OwnershipCommand): ProcessOwnership =
        assertIs<OwnershipReduction.Changed>(reduceOwnership(command)).record

    internal class Server : GitHubTransport {
        override val isAvailable = true
        val files = mutableMapOf<String, String>()
        var defaultBranch = "main"
        var hasAccess = true
        var hasBranch = true
        var loseNextResponse = false
        var staleRead: String? = null
        var writes = 0
        var branchCreates = 0
        private val lock = Mutex()
        private var beforePut: suspend () -> Unit = {}

        fun rendezvous() {
            var arrivals = 0
            val ready = CompletableDeferred<Unit>()
            beforePut = {
                arrivals++
                if (arrivals == 2) ready.complete(Unit)
                ready.await()
            }
        }

        override suspend fun call(request: GitHubRequest): GitHubResponse {
            if (!hasAccess) return GitHubResponse(404)
            val path = request.path.substringBefore('?')
            if (request.method == "PUT") beforePut()
            return lock.withLock {
                when {
                    path in setOf("repos/owner/repo", "repos/owner/repo/issues/1") -> metadata(path)

                    path.endsWith("git/refs") -> {
                        hasBranch = true
                        branchCreates++
                        GitHubResponse(201)
                    }

                    "/git/ref/" in path -> if (hasBranch || path.endsWith("/main")) {
                        GitHubResponse(
                            200,
                            buildJsonObject {
                                put(
                                    "object",
                                    buildJsonObject {
                                        put("type", "commit")
                                        put("sha", "0".repeat(40))
                                    },
                                )
                            },
                        )
                    } else {
                        GitHubResponse(404)
                    }

                    request.method == "GET" -> {
                        val value = staleRead ?: files[path]
                        if (value == null || !hasBranch) GitHubResponse(404) else file(value)
                    }

                    request.method == "PUT" -> update(path, checkNotNull(request.body).jsonObject)

                    else -> error("Unexpected coordinator request")
                }
            }
        }

        private fun metadata(path: String): GitHubResponse = GitHubResponse(
            200,
            if (path.endsWith("/issues/1")) {
                buildJsonObject {
                    put("node_id", "I_task")
                    put("number", 1)
                }
            } else {
                buildJsonObject {
                    put("node_id", "R_repository")
                    put("default_branch", defaultBranch)
                    put("permissions", buildJsonObject { put("push", true) })
                }
            },
        )

        private fun update(path: String, body: JsonObject): GitHubResponse {
            assertEquals(OWNERSHIP_BRANCH, body["branch"]?.jsonPrimitive?.content)
            val old = files[path]
            val sha = body["sha"]?.jsonPrimitive?.content
            val conflictsCreate = old == null && sha != null
            val conflictsUpdate = old != null && blobHash(old) != sha
            if (conflictsCreate || conflictsUpdate) return GitHubResponse(409)
            val content = checkNotNull(body["content"]?.jsonPrimitive?.content?.decodeBase64()).utf8()
            files[path] = content
            writes++
            return if (loseNextResponse) {
                loseNextResponse = false
                GitHubResponse(0)
            } else {
                GitHubResponse(
                    201,
                    buildJsonObject {
                        put("content", buildJsonObject { put("sha", blobHash(content)) })
                    },
                )
            }
        }

        private fun file(content: String): GitHubResponse = GitHubResponse(
            200,
            buildJsonObject {
                put("type", "file")
                put("encoding", "base64")
                put("sha", blobHash(content))
                put("content", content.encodeUtf8().base64())
            },
        )
    }
}
