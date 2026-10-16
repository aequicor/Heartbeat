package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportNote
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportReport
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet

/**
 * An import of the engine's own configuration: what it would bring from [source], the parts chosen to import and
 * how they combine with the profile ([isReplacing]). Never printed: the source names the user's home.
 */
@Immutable
data class ImportUi(
    val source: String,
    val items: ImmutableList<ImportItemUi>,
    val selected: ImmutableSet<String> = persistentSetOf(),
    val isReplacing: Boolean = false,
) {
    override fun toString(): String = "ImportUi(items=${items.size}, selected=${selected.size})"
}

/** One importable part with how many entries it holds and what the import changes in it. */
@Immutable
data class ImportItemUi(val id: String, val title: TextUi, val count: Int?, val notes: ImmutableList<ImportNoteUi>)

/** Something an import changes or skips, at [path] of its part. */
@Immutable
data class ImportNoteUi(val reason: ImportNoteReasonUi, val path: String)

/** Why an import note was made; mirrors the facade reasons. */
enum class ImportNoteReasonUi {
    OwnedKeyDropped,
    SecretMovedToVault,
    HostVariableNotPassed,
    Skipped,
    TooLarge,
    Unreadable,
}

/** What a finished import brought, by part title, and what it changed or skipped. */
@Immutable
data class ImportReportUi(val imported: ImmutableList<TextUi>, val notes: ImmutableList<ImportNoteUi>)

/** The panel's import of [preview], keeping the choice of [current] while the parts are the same. */
internal fun ImportPreview.toUi(current: ImportUi?): ImportUi {
    val items = items.map { it.toUi() }.toImmutableList()
    val ids = items.map { it.id }.toSet()
    val isSame = current != null && current.items.map { it.id }.toSet() == ids
    return ImportUi(
        source = source,
        items = items,
        selected = if (isSame) current.selected else ids.toImmutableSet(),
        isReplacing = isSame && current.isReplacing,
    )
}

/** The report of a finished import, with the titles of the parts the [preview] listed. */
internal fun ImportReport.toUi(preview: ImportUi?): ImportReportUi = ImportReportUi(
    imported.map { id -> preview?.items?.firstOrNull { it.id == id }?.title ?: TextUi(id, persistentMapOf()) }
        .toImmutableList(),
    notes.map { it.toUi() }.toImmutableList(),
)

private fun ImportItem.toUi() = ImportItemUi(id, title.toUi(), count, notes.map { it.toUi() }.toImmutableList())

private fun ImportNote.toUi() = ImportNoteUi(ImportNoteReasonUi.valueOf(reason.name), path.joinToString("."))
