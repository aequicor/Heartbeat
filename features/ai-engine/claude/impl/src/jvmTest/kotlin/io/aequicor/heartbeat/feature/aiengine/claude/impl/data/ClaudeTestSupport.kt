package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.claude.api.ClaudeEngine
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

internal val testTarget = EngineTarget(ClaudeEngine.Id, EngineBindingId("binding"), ModelId("sonnet"))
internal fun prompt(id: String = "request") = PromptRequest(RequestId(id), listOf(ContentPart.Text("Hello")))
internal fun <F : EngineFeature> EngineFeatures.available(key: EngineFeatureKey<F>): F =
    assertIs<FeatureAccess.Available<F>>(resolve(key)).feature

internal class FakeClaudeTransport : ClaudeTransport {
    var account = "owner@example.test"
    var loggedIn = true
    var method = "claude.ai"
    val calls = mutableListOf<List<String>>()
    var generation: suspend (List<String>, suspend (String) -> Boolean) -> Int = { args, line ->
        val id = args.first { it.startsWith("--session-id=") || it.startsWith("--resume=") }.substringAfter('=')
        line(initFrame(id))
        line(assistantFrame(id))
        line(resultFrame(id))
        0
    }
    override suspend fun run(
        arguments: List<String>,
        input: String,
        workspace: WorkspaceRef?,
        closeInput: Boolean,
        line: suspend (String) -> Boolean,
    ): Int {
        calls += arguments
        return if (arguments == listOf("auth", "status")) {
            line("""{"loggedIn":$loggedIn,"authMethod":"$method","email":"$account","orgId":"organization"}""")
            if (loggedIn) 0 else 1
        } else {
            generation(arguments, line)
        }
    }
}

internal class TestClaudeToggles : FeatureToggles {
    var enabled = true

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = enabled as T

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(enabled as T)
}

internal class ClaudeFixture(val scope: CoroutineScope) {
    val transport = FakeClaudeTransport()
    val toggles = TestClaudeToggles()
    val account = ClaudeAccount(
        transport,
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(1)
        },
    )
    suspend fun runtime(): ClaudeRuntime {
        val revision = account.inspect().check.revision
        return ClaudeRuntime(
            RuntimeIdentity(ClaudeEngine.Id, ClaudeEngine.AuthSource, revision),
            transport,
            account,
            toggles,
            scope,
        )
    }
}

internal fun initFrame(id: String) = """{"type":"system","subtype":"init","session_id":"$id","model":"claude-actual"}"""
internal fun assistantFrame(id: String) = """{"type":"assistant","session_id":"$id",
        "message":{"model":"claude-actual","content":[{"type":"text","text":"Answer"}]}}"""
internal fun resultFrame(id: String) = """{"type":"result","session_id":"$id",
        "subtype":"success","is_error":false,"result":"Answer"}"""
