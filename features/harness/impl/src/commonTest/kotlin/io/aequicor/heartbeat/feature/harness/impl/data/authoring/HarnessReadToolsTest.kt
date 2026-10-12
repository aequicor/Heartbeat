package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCatalogEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.ItemStatus
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.code
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HARNESS_CONTEXT_CHARS
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.harness
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.dispatchSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessReadToolsTest {
    private val workflow = HarnessItem.Workflow(ItemId("wf"), ItemName("screen"), "Build a screen", "workflow { it }")
    private val owner = harness.copy(
        items = listOf(code, workflow),
        tools = ToolPolicySpec(setOf("web_search"), mapOf("claude" to mapOf("Bash" to ToolSwitch.On))),
    )
    private val other = harness.copy(
        id = HarnessId("other"),
        name = HarnessName("other"),
        title = "Other",
        isEnabled = false,
        items = emptyList(),
    )
    private val toggles = ReadToggles()
    private val library = ReadLibrary(
        HarnessState.Ready(
            listOf(
                HarnessEntry(
                    owner,
                    mapOf(code.id to ItemStatus.Failed(1, isDisabled = true), workflow.id to ItemStatus.Active(2)),
                ),
                HarnessEntry(other),
            ),
            attachments = mapOf(dispatchSession to setOf(owner.id)),
        ),
    )
    private val tools = HarnessReadTools(
        toggles,
        lazyOf(library),
        lazyOf(ReadCatalogs),
        lazyOf(HarnessActiveAccess { _, _ -> listOf(owner) }),
    )
    private val context = AgentToolContext(dispatchSession, WorkspaceRef("project"), TurnId("turn"))

    @Test
    fun `list shows state counts and explicit connections`() = runTest {
        val lines = tools.execute(context, HarnessTools.LIST, JsonObject(emptyMap())).text.lines()
        assertEquals(
            "- test — Test · on · 1 script, 1 workflow · connected here · active here",
            lines[0],
        )
        assertEquals("- other — Other · off · no items", lines[1])
    }

    @Test
    fun `list marks a covering harness beyond the active limit as restricting here`() = runTest {
        val overflow = object : HarnessActiveAccess {
            override suspend fun active(workspace: WorkspaceRef?, session: SessionRef?): List<Harness> = emptyList()
            override suspend fun applicable(workspace: WorkspaceRef?, session: SessionRef?) = listOf(owner)
        }
        val limited = HarnessReadTools(toggles, lazyOf(library), lazyOf(ReadCatalogs), lazyOf(overflow))
        val lines = limited.execute(context, HarnessTools.LIST, JsonObject(emptyMap())).text.lines()
        assertTrue(lines[0].endsWith(" · connected here · restricts here"), lines[0])
        assertFalse(lines[1].contains(" here"), lines[1])
    }

    @Test
    fun `get describes item statuses and the tool policy, and returns one item on request`() = runTest {
        val described = tools.execute(context, HarnessTools.GET, ref("test")).text
        assertTrue(described.contains("Activation: connected chats only · on · revision 0"), described)
        assertTrue(described.contains("- script script · failed and disabled"), described)
        assertTrue(described.contains("- workflow screen · active — Build a screen"), described)
        assertTrue(described.endsWith("Tool policy: Heartbeat web_search off; claude Bash on"), described)
        val item = tools.execute(context, HarnessTools.GET, ref("test", item = "screen")).text
        assertTrue(item.startsWith("workflow { it }\n\nInput schema: {}"), item)
        assertTrue(tools.execute(context, HarnessTools.GET, ref("nope")).isError)
        assertTrue(tools.execute(context, HarnessTools.GET, ref("test", item = "nope")).isError)
        assertTrue(tools.execute(context, HarnessTools.GET, JsonObject(emptyMap())).isError)
    }

    @Test
    fun `tools catalog shows effective switches, defaults and what can be switched on`() = runTest {
        val text = tools.execute(context, HarnessTools.TOOLS_CATALOG, JsonObject(emptyMap())).text
        assertTrue(text.contains("- Поиск [search]: web_search (read)"), text)
        assertTrue(text.contains("native enabling is off (flag harness.native_tools)"), text)
        assertTrue(text.contains("Bash (command, on, on/off)"), text)
        assertTrue(text.contains("WebFetch (command, off, on/off)"), text)
        assertTrue(text.contains("Agent (command, on by default, off only)"), text)
        assertTrue(text.contains("Grep (read, off by default, on/off)"), text)
        assertFalse(text.contains("Silent"), text)
        toggles.isNativeEnabling = true
        val enabled = tools.execute(context, HarnessTools.TOOLS_CATALOG, JsonObject(emptyMap())).text
        assertTrue(enabled.contains("native enabling is available"), enabled)
    }

    @Test
    fun `the reference serves known topics with the shared limits and refuses others`() = runTest {
        val guide = tools.execute(context, HarnessTools.API_REFERENCE, buildJsonObject { put("topic", "guide") })
        assertFalse(guide.isError)
        assertTrue(guide.text.contains("shares $HARNESS_CONTEXT_CHARS characters per chat"), guide.text)
        assertTrue(guide.text.contains("${HarnessLimits.RUN_TIME} per run"), guide.text)
        val unknown = tools.execute(context, HarnessTools.API_REFERENCE, buildJsonObject { put("topic", "nope") })
        assertTrue(unknown.isError)
    }

    @Test
    fun `nothing is read while harnesses are turned off`() = runTest {
        toggles.isEnabled = false
        val result = tools.execute(context, HarnessTools.LIST, JsonObject(emptyMap()))
        assertTrue(result.isError && result.text == "Harnesses are turned off", result.text)
        assertTrue(tools.specifications(null).isEmpty())
    }

    private fun ref(harness: String, item: String? = null): JsonObject = buildJsonObject {
        put("harness", harness)
        item?.let { put("item", it) }
    }
}

private class ReadToggles : FeatureToggles {
    var isEnabled = true
    var isNativeEnabling = false

    @Suppress("UNCHECKED_CAST") // Only boolean flags are read.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = when (toggle) {
        HarnessEnabled -> MutableStateFlow(isEnabled)
        HarnessNativeTools -> MutableStateFlow(isNativeEnabling)
        else -> error("Unexpected toggle")
    } as Flow<T>

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = observe(toggle).first()
}

private class ReadLibrary(private val state: HarnessState.Ready) : HarnessLibraryClient {
    override val current: HarnessState.Ready = state

    override suspend fun ready(): HarnessState.Ready = state

    override suspend fun submit(intent: HarnessIntent.Command): LibraryOutcome = error("Reads never write")
}

private object ReadCatalogs : HarnessToolCatalogs {
    override fun nativeEnabling() = MutableStateFlow(false)

    override fun hosted() = listOf(
        ToolGroup("search", "Поиск", listOf(ToolCatalogEntry("web_search", AgentToolAction.Read))),
    )

    override fun engines() = listOf(
        EngineDescriptor(
            EngineId("claude"),
            "Claude",
            EngineFamily.Vendor,
            emptySet(),
            HarnessNativeTools,
            nativeTools = listOf(
                NativeToolSpec("Bash", AgentToolAction.Command, isEnabledByDefault = false, isGated = true),
                NativeToolSpec("WebFetch", AgentToolAction.Command, isEnabledByDefault = true, isGated = true),
                NativeToolSpec("Agent", AgentToolAction.Command, isEnabledByDefault = true, isGated = false),
                NativeToolSpec("Grep", AgentToolAction.Read, isEnabledByDefault = false, isGated = true),
            ),
        ),
        EngineDescriptor(EngineId("silent"), "Silent", EngineFamily.Vendor, emptySet(), HarnessNativeTools),
    )

    override suspend fun effective(scope: ToolPolicyScope) =
        ResolvedToolPolicy(nativeOn = setOf("Bash"), nativeOff = setOf("WebFetch"))
}
