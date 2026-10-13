package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor

import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecCheck
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDiagnostic
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecRules
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.checkUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.formatUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.PlacementUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.toUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The saved [surface] being edited with what checks of its drafts need: the names of the owner's surfaces and the
 * owner's [methods] (null while unknown, which makes calls only warnings).
 */
internal data class EditedSurface(
    val surface: UiSurface,
    val ownerTitle: String,
    val names: Set<String>,
    val methods: Set<String>?,
) {
    override fun toString(): String =
        "EditedSurface(revision=${surface.revision}, methods=${methods?.size ?: "unknown"})"
}

/**
 * The check of the draft [text]: [diagnostics], validity, whether it differs from the saved spec and the [spec] it
 * would draw when valid.
 */
internal data class DraftCheck(
    val text: String,
    val diagnostics: ImmutableList<DiagnosticUi>,
    val isValid: Boolean,
    val isDirty: Boolean,
    val spec: UiSpec?,
) {
    override fun toString(): String = "DraftCheck(isValid=$isValid, isDirty=$isDirty, diagnostics=${diagnostics.size})"
}

/** [surface] of [ready] with the owner's [methods] where they run. */
internal fun editedSurface(ready: ServiceUiState.Ready, surface: UiSurface, methods: UiMethods?): EditedSurface {
    val owner = surface.content.owner
    val info = ready.owners[owner.kind]?.owners?.firstOrNull { it.id == owner.id }
    return EditedSurface(
        surface = surface,
        ownerTitle = info?.title?.takeIf { it.isNotBlank() } ?: owner.id,
        names = ready.library.surfaces.filter { it.content.owner == owner }.mapTo(
            mutableSetOf(),
        ) { it.content.name.value },
        methods = methods?.takeIf { it.isAvailable }?.methods?.mapTo(mutableSetOf()) { it.name },
    )
}

/**
 * The editor for machine [state] whose library holds [edited] (null when it does not). A clean draft follows the saved
 * spec; a dirty one is rebased when only the revision moved and marked [EditorState.isChangedElsewhere] when the saved
 * spec changed meanwhile. The draft is checked separately ([checkDraft]), since checks are too slow for every input.
 */
internal fun EditorState.synced(state: ServiceUiState, edited: EditedSurface?): EditorState = when (state) {
    is ServiceUiState.Disabled -> copy(phase = EditorPhase.Disabled)

    is ServiceUiState.Loading -> copy(phase = EditorPhase.Loading)

    is ServiceUiState.Failed -> copy(phase = EditorPhase.Failed)

    is ServiceUiState.Ready -> if (edited == null) {
        copy(phase = EditorPhase.Missing)
    } else {
        rebased(edited.surface).copy(phase = EditorPhase.Ready, info = edited.info())
    }
}

/** The saved spec of [surface] replaces the draft, keeping its layout when it already says the same. */
internal fun EditorState.adopted(surface: UiSurface): EditorState {
    val saved = surface.content.spec
    val text = if (compact(text) == saved) text else formatUiSpec(saved, isIndented = true) ?: saved
    return copy(
        text = text,
        base = surface.revision,
        baseSpec = saved,
        isDirty = false,
        isChecked = this.text == text && isChecked,
        isChangedElsewhere = false,
    )
}

/** The user typed [next]: the draft is dirty until a check finds it equal to the saved spec. */
internal fun EditorState.typed(next: String): EditorState =
    if (next == text) this else copy(text = next, isDirty = true, isChecked = false)

/** Checks [text] against [edited] in its stored compact form, as the agent's writes are checked. */
internal fun checkDraft(text: String, edited: EditedSurface): DraftCheck {
    val content = edited.surface.content
    val compact = compact(text)
    val rules = UiSpecRules(UiSpecOrigin.Library, content.placement, edited.methods, edited.names)
    val check = checkUiSpec(compact ?: text, rules)
    val diagnostics = when (check) {
        is UiSpecCheck.Valid -> check.warnings
        is UiSpecCheck.Invalid -> check.diagnostics
    }
    return DraftCheck(
        text = text,
        diagnostics = diagnostics.map { it.toUi() }.toImmutableList(),
        isValid = check is UiSpecCheck.Valid,
        isDirty = compact != content.spec,
        spec = (check as? UiSpecCheck.Valid)?.spec,
    )
}

/** The editor with [check] applied, unless the draft changed since it was checked. */
internal fun EditorState.withCheck(check: DraftCheck): EditorState = if (check.text != text) {
    this
} else {
    copy(diagnostics = check.diagnostics, isValid = check.isValid, isDirty = check.isDirty, isChecked = true)
}

private fun EditorState.rebased(surface: UiSurface): EditorState {
    val saved = surface.content.spec
    return when {
        !isDirty -> adopted(surface)
        compact(text) == saved -> copy(base = surface.revision, baseSpec = saved, isChangedElsewhere = false)
        saved == baseSpec -> copy(base = surface.revision, isChangedElsewhere = false)
        else -> copy(isChangedElsewhere = true)
    }
}

private fun EditedSurface.info() = SurfaceInfoUi(
    title = surface.content.title,
    description = surface.content.description,
    owner = ownerTitle,
    placement = surface.content.placement.toUi(),
    launchers = surface.content.launchers.sorted().map { it.toUi() }.toImmutableList(),
    revision = surface.revision,
    isEnabled = surface.isEnabled,
)

private fun UiSlot.toUi(): PlacementUi = when (this) {
    UiSlot.Composer -> PlacementUi.Composer
    UiSlot.Start -> PlacementUi.Start
}

private fun UiSpecDiagnostic.toUi() = DiagnosticUi(
    path = path,
    message = buildString {
        append(problem.message)
        detail?.let { append(" (").append(it).append(')') }
    },
    isWarning = isWarning,
)

/** The stored form of a draft, or null when it is not JSON within the limits. */
private fun compact(text: String): String? = formatUiSpec(text, isIndented = false)
