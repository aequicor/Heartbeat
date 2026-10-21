package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reproduces the three reported directory listings at Pi's RPC boundary without executing a shell.
 * This spy verifies adapter classification only; PowerShellListingPermissionsTest in facade tests the real gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PiPowerShellListingTest {
    @Test
    fun `directory listings reach the shared gate as commands with complete arguments`() = runTest {
        for (command in DirectoryListings) {
            val arguments = JsonObject(mapOf("command" to JsonPrimitive(command)))
            val calls = mutableListOf<NativeToolCall>()
            val tools = object : ProfileAgentTools by NoAgentTools {
                override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict {
                    calls += call
                    return NativeVerdict.Deny("Adapter observation only")
                }
            }
            val fixture = fixture(this, tools = tools)
            fixture.runningTurn()
            val request = approval("listing", command, "powershell")
            val payload = record(checkNotNull(request.string("message")))
            fixture.connection.event(
                JsonObject(
                    request - "message" + mapOf(
                        "method" to JsonPrimitive("input"),
                        "placeholder" to JsonPrimitive(JsonObject(payload + ("arguments" to arguments)).toString()),
                    ),
                ),
            )
            runCurrent()
            val call = calls.single()
            assertEquals("powershell", call.name, command)
            assertEquals(AgentToolAction.Command, call.action, command)
            assertEquals(command, call.command)
            assertEquals(arguments, call.arguments)
            assertEquals(emptyList(), call.paths, command)
            assertEquals(TestWorkspace.toString(), call.workingDirectory, command)
            assertEquals(
                listOf(valueAnswer("listing", """{"allowed":false,"reason":"Adapter observation only"}""")),
                fixture.connection.sent,
            )
            fixture.session.shutdown()
        }
    }
}

private val DirectoryListings = listOf(
    "Get-ChildItem -Path features/ai-engine -Directory -Recurse -Depth 2 | Select-Object -ExpandProperty FullName",
    "Get-ChildItem -Path core -Directory | Select-Object -ExpandProperty FullName",
    "Get-ChildItem -Directory | Select-Object -ExpandProperty Name",
)
