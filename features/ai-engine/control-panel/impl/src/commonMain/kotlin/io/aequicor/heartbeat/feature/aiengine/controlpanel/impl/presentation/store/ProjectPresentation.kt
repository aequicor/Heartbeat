package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.ControlOperation
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigCapability
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectEdit
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Whether a project's executable configuration loads. */
enum class ProjectTrustUi { Undecided, Content, Trusted }

/** A saved project the engine can be configured in, with its trust decision. */
@Immutable
data class ProjectItemUi(val id: String, val title: String, val trust: ProjectTrustUi)

/**
 * A project's configuration as the panel edits it: its [form] (project fields with the profile values as their
 * defaults, the project's files) and the [trust] decision when the engine asks for one.
 */
@Immutable
data class ProjectFormUi(val id: String, val title: String, val trust: ProjectTrustUi?, val form: ConfigFormUi)

/** Unsaved changes of a project's files: [draft] edited from the files read at [revision]. Never printed. */
data class ProjectDraft(val workspace: WorkspaceRef, val revision: ProjectRevision, val draft: ConfigDraft) {
    override fun toString(): String = "ProjectDraft(draft=$draft)"
}

/** The [projects] with the trust decided for each; none when the engine configures nothing in projects. */
internal fun EngineConfigState.projectItems(projects: List<LocalWorkspace>): List<ProjectItemUi> =
    if (schema.documents.none { ConfigScope.Project in it.scopes }) {
        emptyList()
    } else {
        projects.map { ProjectItemUi(it.ref.value, it.name, trustOf(it.ref)) }
    }

/** The form of [project] with [draft] applied; a draft read at another revision of the files is in conflict. */
internal fun EngineConfigState.projectForm(project: ProjectConfig, draft: ProjectDraft?, title: String): ProjectFormUi {
    val own = draft?.takeIf { it.workspace == project.workspace }
    val form = projectSource(project).form(own?.draft, isUnparsedBlocking = false)
        .copy(hasConflict = own != null && !own.draft.isSubmitted && own.revision != project.revision)
    val asksTrust = ConfigCapability.ProjectTrust in schema.capabilities
    return ProjectFormUi(project.workspace.value, title, trustOf(project.workspace).takeIf { asksTrust }, form)
}

/**
 * [draft] (or a new one from [project]) after [input] for the field [fieldId]. A field of a file that exists but
 * does not parse stays as it is: editing it would replace the file the user wrote.
 */
internal fun EngineConfigState.editProject(
    project: ProjectConfig,
    draft: ProjectDraft?,
    fieldId: String,
    input: FieldInputUi,
): ProjectDraft? {
    val current = draft?.takeIf { it.workspace == project.workspace } ?: project.newDraft()
    val field = schema.field(fieldId)
    val isUnparsed = field != null && field.binding.document in project.unreadable &&
        current.draft.values.documents[field.binding.document] == null
    if (current.draft.isSubmitted || field == null || isUnparsed) return draft
    return current.copy(draft = projectSource(project).edit(current.draft, fieldId, input))
}

/** [draft] (or a new one from [project]) with the project file [id] replaced by [text]. */
internal fun EngineConfigState.editProjectFile(
    project: ProjectConfig,
    draft: ProjectDraft?,
    id: String,
    text: String,
): ProjectDraft? {
    val current = draft?.takeIf { it.workspace == project.workspace } ?: project.newDraft()
    if (current.draft.isSubmitted) return draft
    return current.copy(draft = projectSource(project).editFile(current.draft, id, text))
}

/** The project save of [draft]: its values as saved, and JSON files that do not parse written as typed. */
internal fun EngineConfigState.projectSaveOf(draft: ProjectDraft): ProjectEdit {
    val raw = draft.draft.files.filter { (id, text) ->
        schema.document(ConfigDocumentId(id))?.format == ConfigFormat.Json &&
            text.isNotBlank() && parseJsonObject(text).isFailure
    }
    return ProjectEdit(schema.cleaned(draft.draft.values), raw.mapKeys { ConfigDocumentId(it.key) })
}

/**
 * The project draft as the machine moved on: dropped when its project closed, kept while its save runs, editable
 * again when the save failed and dropped once the files were read anew after a successful save.
 */
internal fun ProjectDraft?.settle(project: ProjectConfig?, state: EngineControlState.Active): ProjectDraft? = when {
    this == null || project?.workspace != workspace -> null
    !draft.isSubmitted || state.pending is ControlOperation.SaveProject -> this
    state.failed?.operation is ControlOperation.SaveProject -> copy(draft = draft.copy(isSubmitted = false))
    project.revision != revision -> null
    else -> this
}

/** The facade's trust of a panel choice. */
internal fun ProjectTrustUi.toTrust(): ProjectTrust = ProjectTrust.valueOf(name)

private fun ProjectConfig.newDraft() = ProjectDraft(workspace, revision, ConfigDraft(null, values))

private fun EngineConfigState.trustOf(workspace: WorkspaceRef): ProjectTrustUi =
    ProjectTrustUi.valueOf((trust[workspace] ?: ProjectTrust.Undecided).name)

private fun EngineConfigState.projectSource(project: ProjectConfig) = FormSource(
    schema,
    ConfigScope.Project,
    project.values,
    inherited = profile,
    unparsed = project.unreadable.mapKeys { it.key.value },
)
