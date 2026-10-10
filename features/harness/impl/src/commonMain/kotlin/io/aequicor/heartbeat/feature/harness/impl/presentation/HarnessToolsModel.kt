package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.ToolPolicySpec
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessToolCatalogs
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock

/** Tool policy of one harness: Heartbeat tools that can be turned off and native switches per engine. */
@Immutable
internal data class HarnessToolsState(
    val phase: PhaseUi = PhaseUi.Loading,
    val harnessName: String = "",
    val groups: ImmutableList<HostedGroupUi> = persistentListOf(),
    val engines: ImmutableList<EngineToolsUi> = persistentListOf(),
    val isNativeEnablingAvailable: Boolean = false,
    val isSaveFailed: Boolean = false,
) : MVIState

/** Controls of the tool policy screen. */
internal sealed interface HarnessToolsIntent : MVIIntent {
    data class SetHostedOff(val tool: String, val isOff: Boolean) : HarnessToolsIntent
    data class SetNative(val engine: String, val tool: String, val choice: NativeChoiceUi) : HarnessToolsIntent
    data object DismissError : HarnessToolsIntent
}

/** Reserved contract for one-off actions. */
internal sealed interface HarnessToolsAction : MVIAction

private typealias ToolsPipeline = PipelineContext<HarnessToolsState, HarnessToolsIntent, HarnessToolsAction>

private val log = Log.tag("HarnessHost")

/** Every switch is one user edit of the stored policy; Off wins over On when several harnesses are active. */
internal class HarnessToolsModel(
    harness: String,
    private val machine: HarnessMachine,
    private val catalogs: HarnessToolCatalogs,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val id = HarnessId(harness)

    val store = factory.create<HarnessToolsState, HarnessToolsIntent, HarnessToolsAction>(
        "HarnessTools",
        HarnessToolsState().reflectPolicy(machine.state.value),
        onError = { this },
    ) {
        reflect(machine) { reflectPolicy(it) }
        whileSubscribed {
            catalogs.nativeEnabling().collect { isAvailable ->
                updateState { copy(isNativeEnablingAvailable = isAvailable) }
            }
        }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ToolsPipeline.handle(intent: HarnessToolsIntent) {
        val harness = (machine.state.value as? HarnessState.Ready)?.harnesses
            ?.firstOrNull { it.harness.id == id }?.harness
        when (intent) {
            HarnessToolsIntent.DismissError -> updateState { copy(isSaveFailed = false) }

            is HarnessToolsIntent.SetHostedOff -> harness?.let {
                val off = if (intent.isOff) it.tools.hostedOff + intent.tool else it.tools.hostedOff - intent.tool
                save(it.revision, it.tools.copy(hostedOff = off))
            }

            is HarnessToolsIntent.SetNative -> harness?.let {
                val engine = it.tools.native[intent.engine].orEmpty()
                val switches = when (intent.choice) {
                    NativeChoiceUi.Default -> engine - intent.tool
                    NativeChoiceUi.On -> engine + (intent.tool to ToolSwitch.On)
                    NativeChoiceUi.Off -> engine + (intent.tool to ToolSwitch.Off)
                }
                val native = (it.tools.native + (intent.engine to switches))
                    .filterValues { tools -> tools.isNotEmpty() }
                save(it.revision, it.tools.copy(native = native))
            }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ToolsPipeline.save(revision: Long, tools: ToolPolicySpec) {
        if (catalogs.problem(tools) != null) {
            log.i { "Tool policy edit does not match the current catalogs" }
            updateState { copy(isSaveFailed = true) }
            return
        }
        val intent = HarnessIntent.Public.Update(
            request(),
            id,
            HarnessChange.Tools(tools),
            revision,
            HarnessAuthor.User,
            clock.now(),
        )
        sendTo(machine, intent) { updateState { copy(isSaveFailed = true) } }
    }

    private fun HarnessToolsState.reflectPolicy(state: HarnessState): HarnessToolsState {
        val ready = state as? HarnessState.Ready ?: return copy(phase = state.phase())
        val harness = ready.harnesses.firstOrNull { it.harness.id == id }?.harness
            ?: return copy(phase = PhaseUi.NotFound)
        return copy(
            phase = PhaseUi.Ready,
            harnessName = harness.name.value,
            groups = catalogs.hosted().mapNotNull { it.toUi(harness.tools) }.toImmutableList(),
            engines = catalogs.engines().filter { it.nativeTools.isNotEmpty() }
                .map { it.toUi(harness.tools) }.toImmutableList(),
        )
    }
}

/** Harness tools are never offered: a harness cannot switch off its own repair path. */
private fun ToolGroup.toUi(spec: ToolPolicySpec): HostedGroupUi? {
    val tools = tools.filterNot { it.name.startsWith("harness_") || it.name.startsWith(HarnessTools.SCRIPT_PREFIX) }
        .map { HostedToolUi(it.name, it.name in spec.hostedOff) }.toImmutableList()
    return tools.takeIf { it.isNotEmpty() }?.let { HostedGroupUi(id, title, it) }
}

private fun EngineDescriptor.toUi(spec: ToolPolicySpec): EngineToolsUi {
    val switches = spec.native[id.value].orEmpty()
    return EngineToolsUi(
        id = id.value,
        title = title,
        tools = nativeTools.map { tool ->
            NativeToolUi(
                name = tool.name,
                choice = when (switches[tool.name]) {
                    ToolSwitch.On -> NativeChoiceUi.On
                    ToolSwitch.Off -> NativeChoiceUi.Off
                    null -> NativeChoiceUi.Default
                },
                isEnabledByDefault = tool.isEnabledByDefault,
                isOnAllowed = tool.isGated,
            )
        }.toImmutableList(),
        areNativeCallsHooked = nativeTools.any { it.isGated },
    )
}
