@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** A workspace column as the screen shows it. */
@Serializable
@SerialName("PaneUi")
@Immutable
data class PaneUi(
    val id: Int,
    val sessionId: String? = null,
    val projectId: String? = null,
    val isCreating: Boolean = false,
    val isWorktree: Boolean = false,
    val isOrganism: Boolean = false,
    /** Source-only isolation selected for a new pane. */
    val isSandbox: Boolean = false,
    /** Planning mode selected for a new pane. */
    val isPlanning: Boolean = false,
)

/** Where a project's agent runs. */
@Serializable
@SerialName("EnvironmentUi")
enum class EnvironmentUi { Local, Cloud }

/** Reasoning budget choices of the composer. */
@Serializable
@SerialName("EffortUi")
enum class EffortUi { Low, Medium, High, VeryHigh }

/** Approval choices of the composer. */
@Serializable
@SerialName("ApprovalUi")
enum class ApprovalUi { Ask, AutoEdits, AutoApprove }

/**
 * A model offered by the composer. [connectionKey] names its engine connection: a running chat may switch only
 * between models sharing it; null for scripted demo models and engines that cannot switch a session's model.
 */
@Serializable
@SerialName("ModelUi")
@Immutable
data class ModelUi(
    val id: String,
    val name: String,
    val isResearchSupported: Boolean = false,
    val isLocalProjectSupported: Boolean = false,
    val shortName: String = name,
    val reasoningEfforts: ImmutableList<String> = persistentListOf(),
    val defaultReasoningEffort: String? = null,
    val connectionKey: String? = null,
    val isTrustSupported: Boolean = false,
    val inputSupport: InputSupportUi = InputSupportUi(),
    /** A demo model of the scripted agent; its approval and effort preferences are local behavior. */
    val isScripted: Boolean = false,
)

/** Composer preferences mirrored from the machine. */
@Serializable
@SerialName("SettingsUi")
@Immutable
data class SettingsUi(
    val modelId: String,
    val effort: EffortUi,
    val approval: ApprovalUi,
    val engineEfforts: ImmutableMap<String, String> = persistentMapOf(),
    val nativeEffort: String? = null,
)

/** Confirmed configuration of one native conversation; pending operations never replace the applied values. */
@Serializable
@SerialName("SessionConfigurationUi")
@Immutable
data class SessionConfigurationUi(
    val modelId: String,
    val reasoningEffort: String?,
    val approval: ApprovalUi,
    val pendingOperation: String? = null,
)

/** Preferences of a fresh workspace; equals the domain `DefaultRunSettings`. */
val DefaultSettingsUi: SettingsUi = SettingsUi("pulse-pro", EffortUi.High, ApprovalUi.Ask)

/**
 * Harnesses of the composer picker: enabled [options], committed connections by chat id and choices kept on the panes
 * of chats that have no session yet. An absent pane follows the current available defaults; a present pane is an
 * explicit override, including an empty set. [failedChats] are chats whose last connection change was not confirmed.
 */
@Serializable
@SerialName("HarnessChoicesUi")
@Immutable
data class HarnessChoicesUi(
    val options: ImmutableList<HarnessOptionUi> = persistentListOf(),
    val chats: ImmutableMap<String, ImmutableSet<String>> = persistentMapOf(),
    val panes: ImmutableMap<Int, ImmutableSet<String>> = persistentMapOf(),
    val failedChats: ImmutableSet<String> = persistentSetOf(),
    /** False until defaults are known; disabled harnesses have a known empty snapshot. */
    val isReady: Boolean = true,
    val isLoadFailed: Boolean = false,
)

/** An enabled harness of the library; [projectIds] restrict where it is offered (empty: everywhere). */
@Serializable
@SerialName("HarnessOptionUi")
@Immutable
data class HarnessOptionUi(
    val id: String,
    val title: String,
    val description: String = "",
    val projectIds: ImmutableSet<String> = persistentSetOf(),
    val isDefaultForNewSessions: Boolean = false,
) {
    /** Project restrictions limit both discovery and the choices selected by default for new chats. */
    fun isAvailableFor(projectId: String?): Boolean = projectIds.isEmpty() || projectId in projectIds
}

/** One harness of a pane's picker. */
@Serializable
@SerialName("HarnessChoiceUi")
@Immutable
data class HarnessChoiceUi(val id: String, val title: String, val isSelected: Boolean, val description: String = "")
