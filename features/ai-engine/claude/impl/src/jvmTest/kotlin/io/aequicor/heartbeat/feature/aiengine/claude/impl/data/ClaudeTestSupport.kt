package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock
import kotlin.time.Instant

internal class FakeClaudeTransport : ClaudeTransport {
    var account = "owner@example.test"
    var loggedIn = true
    var method = "claude.ai"
    val calls = mutableListOf<List<String>>()
    var beforeRun: suspend (List<String>) -> Unit = {}
    var generation: suspend (List<String>, suspend (String) -> Boolean) -> Int = { _, _ -> 0 }
    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        line: suspend (String) -> Boolean,
    ): Int {
        calls += arguments
        beforeRun(arguments)
        return if (arguments == listOf("auth", "status")) {
            line("""{"loggedIn":$loggedIn,"authMethod":"$method","email":"$account","orgId":"organization"}""")
            if (loggedIn) 0 else 1
        } else {
            generation(arguments, line)
        }
    }
}

internal class ClaudeFixture(val scope: CoroutineScope) {
    val transport = FakeClaudeTransport()
    val account = ClaudeAccount(
        transport,
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1)
        },
    )
}
