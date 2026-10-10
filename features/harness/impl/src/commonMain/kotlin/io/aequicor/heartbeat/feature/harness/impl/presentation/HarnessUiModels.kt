package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/** Loading progress shared by the harness screens; NotFound means the addressed harness or item is gone. */
internal enum class PhaseUi { Loading, Failed, NotFound, Ready }

/** How agent edits of texts and metadata are accepted. Code and native enabling always ask. */
internal enum class ApprovalUi { Ask, ByTrust, AcceptAll }

/** Permanent activation scope as shown in settings. */
internal enum class ScopeUi { Attached, Profile, Projects }

/** Item kinds, in the order of the detail screen groups. */
internal enum class ItemKindUi { Instruction, Skill, Template, Script, Workflow }

/** Runtime state of an item; code items show activation, texts only their switch. */
internal enum class ItemStatusUi { None, Disabled, Unsupported, Activating, Active, Failed, FailedDisabled }

/** One harness of the library list. */
@Immutable
internal data class HarnessRowUi(
    val id: String,
    val name: String,
    val title: String,
    val scope: ScopeUi,
    val projectCount: Int,
    val itemCount: Int,
    val isEnabled: Boolean,
    val hasFailures: Boolean,
)

/** One item row of a harness. */
@Immutable
internal data class ItemRowUi(
    val id: String,
    val name: String,
    val kind: ItemKindUi,
    val description: String,
    val isEnabled: Boolean,
    val status: ItemStatusUi,
)

/** A saved project that a harness scope can select. */
@Immutable
internal data class ProjectChoiceUi(val key: String, val name: String, val isSelected: Boolean)

/** A chat explicitly connected to the harness; [label] names the engine and a short chat id. */
@Immutable
internal data class AttachmentUi(val key: String, val label: String)

/** Durable outcome of a workflow run. */
internal enum class RunStatusUi { Running, Cancelling, Completed, Failed, Cancelled }

/** One workflow run of the harness. */
@Immutable
internal data class RunUi(
    val id: String,
    val workflow: String,
    val status: RunStatusUi,
    val steps: Int,
    val awaitingPermissions: Int,
)

/** One compiler message of the item editor; never logged. */
@Immutable
internal data class DiagnosticUi(val line: Int?, val column: Int?, val message: String, val isError: Boolean)

/** A user's choice for one native tool: the engine default or an explicit switch. */
internal enum class NativeChoiceUi { Default, On, Off }

/** A Heartbeat tool in the policy editor. */
@Immutable
internal data class HostedToolUi(val name: String, val isOff: Boolean)

/** A group of Heartbeat tools. */
@Immutable
internal data class HostedGroupUi(val id: String, val title: String, val tools: ImmutableList<HostedToolUi>)

/** A native tool of one engine; [isOnAllowed] is false for tools that can only be turned off. */
@Immutable
internal data class NativeToolUi(
    val name: String,
    val choice: NativeChoiceUi,
    val isEnabledByDefault: Boolean,
    val isOnAllowed: Boolean,
)

/**
 * Native tools of one engine. [areNativeCallsHooked] tells whether hooks and the trust gate see this engine's
 * native calls (engines with switchable native tools); hosted calls are always hooked.
 */
@Immutable
internal data class EngineToolsUi(
    val id: String,
    val title: String,
    val tools: ImmutableList<NativeToolUi>,
    val areNativeCallsHooked: Boolean,
)
