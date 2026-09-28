package io.aequicor.heartbeat.feature.researchchat.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState

internal enum class ResearchPhase { Loading, Ready, Disabled, Error }
internal enum class ResourceKindUi { Website, Document, Image }
internal enum class ResourceScopeUi { Session, Question }

@Immutable
internal data class ResearchSessionUi(val id: String, val title: String, val isSelected: Boolean)

@Immutable
internal data class ResearchQuestionUi(
    val id: String,
    val title: String,
    val isSelected: Boolean,
    val isRunning: Boolean,
)

@Immutable
internal data class ResearchResourceUi(
    val id: String,
    val title: String,
    val detail: String,
    val kind: ResourceKindUi,
    val isShared: Boolean,
    val isSelected: Boolean,
)

@Immutable
internal data class ResearchMessageUi(val id: String, val isUser: Boolean, val text: String)

/** Input drafts follow question identities; all research state is projected from the machine. */
@Immutable
internal data class ResearchScreenState(
    val phase: ResearchPhase = ResearchPhase.Loading,
    val sessions: ImmutableList<ResearchSessionUi> = persistentListOf(),
    val questions: ImmutableList<ResearchQuestionUi> = persistentListOf(),
    val resources: ImmutableList<ResearchResourceUi> = persistentListOf(),
    val messages: ImmutableList<ResearchMessageUi> = persistentListOf(),
    val sessionTitle: String = "",
    val questionTitle: String = "",
    val draft: String = "",
    val questionId: String? = null,
    val drafts: ImmutableMap<String, String> = persistentMapOf(),
    val submittedDrafts: ImmutableMap<String, String> = persistentMapOf(),
    val isRunning: Boolean = false,
    val isEditable: Boolean = false,
    val hasError: Boolean = false,
    val hasQuestionFailed: Boolean = false,
    val isResourceDialogOpen: Boolean = false,
    val resourceKind: ResourceKindUi = ResourceKindUi.Website,
    val resourceScope: ResourceScopeUi = ResourceScopeUi.Question,
    val resourceTitle: String = "",
    val resourceValue: String = "",
    val resourceMediaType: String? = null,
    val selectedSourceScope: ResourceScopeUi = ResourceScopeUi.Session,
    val isFileImportAvailable: Boolean = false,
) : MVIState

internal sealed interface ResearchScreenIntent : MVIIntent {
    sealed interface ResourceEdit : ResearchScreenIntent
    data object Retry : ResearchScreenIntent
    data object NewSession : ResearchScreenIntent
    data class SelectSession(val id: String) : ResearchScreenIntent
    data object NewQuestion : ResearchScreenIntent
    data class SelectQuestion(val id: String) : ResearchScreenIntent
    data class DraftChanged(val value: String) : ResearchScreenIntent
    data object Submit : ResearchScreenIntent
    data object Stop : ResearchScreenIntent
    data class ShowResourceDialog(val isOpen: Boolean) : ResearchScreenIntent
    data class ResourceKindChanged(val kind: ResourceKindUi) : ResourceEdit
    data class ResourceScopeChanged(val scope: ResourceScopeUi) : ResourceEdit
    data class ResourceTitleChanged(val value: String) : ResourceEdit
    data class ResourceValueChanged(val value: String) : ResourceEdit
    data object AddResource : ResearchScreenIntent
    data class SetResourceSelected(val id: String, val isSelected: Boolean) : ResearchScreenIntent
    data class ShareResource(val id: String) : ResearchScreenIntent
    data class RemoveResource(val id: String) : ResearchScreenIntent
    data class SelectSourceScope(val scope: ResourceScopeUi) : ResourceEdit
    data object ImportFile : ResearchScreenIntent
    data object DismissError : ResearchScreenIntent
}

internal sealed interface ResearchScreenAction : MVIAction
