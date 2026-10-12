package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Spacing between children. */
internal enum class GapUi { None, Small, Medium, Large }

/** Semantic color of text and badges. */
internal enum class ToneUi { Neutral, Brand, Success, Warning, Danger }

/** Typography role of text. */
internal enum class TextStyleUi { Body, Caption, Title, Heading, Code }

/** Emphasis of a button. */
internal enum class ButtonStyleUi { Primary, Secondary, Ghost, Danger }

/** A platform widget. */
internal enum class NativeWidgetUi { Date, File, Folder }

/**
 * What pressing a button or a row does: the screen sends it back to its store, which runs [action] with the item it
 * was pressed on ([scope]). [origin] names the pressed node in logs.
 */
@Immutable
internal data class UiPress(val origin: String, internal val action: UiAction, internal val scope: UiScope) {
    override fun toString(): String = "UiPress(origin=$origin)"
}

/** One column of a table. */
@Immutable
internal data class ColumnUi(val title: String, val weight: Float)

/** One row of a table; [press] runs the row action. */
@Immutable
internal data class RowUi(val key: String, val cells: ImmutableList<String>, val press: UiPress?)

/** One choice of a select. */
@Immutable
internal data class OptionUi(val value: String, val label: String)

/** One pair of a key-value list. */
@Immutable
internal data class PairUi(val key: String, val value: String)

/** A resolved node: every binding is replaced by its value. [key] is stable among siblings for Compose. */
@Immutable
internal sealed interface UiNodeUi {
    val key: String

    /** Children stacked vertically. */
    data class Column(override val key: String, val children: ImmutableList<UiNodeUi>, val gap: GapUi) : UiNodeUi

    /** Children side by side. */
    data class Row(
        override val key: String,
        val children: ImmutableList<UiNodeUi>,
        val gap: GapUi,
        val isWrapped: Boolean,
    ) : UiNodeUi

    /** A framed group. */
    data class Card(override val key: String, val title: String?, val children: ImmutableList<UiNodeUi>) : UiNodeUi

    /** A titled group. */
    data class Section(
        override val key: String,
        val title: String,
        val description: String?,
        val children: ImmutableList<UiNodeUi>,
    ) : UiNodeUi

    /** A separator. */
    data class Divider(override val key: String) : UiNodeUi

    /** Empty space. */
    data class Spacer(override val key: String, val size: GapUi) : UiNodeUi

    /** Plain text. */
    data class Text(override val key: String, val text: String, val style: TextStyleUi, val tone: ToneUi) : UiNodeUi

    /** Markdown text. */
    data class Markdown(override val key: String, val text: String) : UiNodeUi

    /** A colored label. */
    data class Badge(override val key: String, val text: String, val tone: ToneUi) : UiNodeUi

    /** Progress of [value] in 0..1, indeterminate when null. */
    data class Progress(override val key: String, val value: Float?, val label: String?) : UiNodeUi

    /** Pairs of a key and a value. */
    data class KeyValue(override val key: String, val items: ImmutableList<PairUi>) : UiNodeUi

    /** An empty state. */
    data class Empty(override val key: String, val title: String, val description: String?) : UiNodeUi

    /** A PlantUML diagram. */
    data class Diagram(override val key: String, val source: String, val title: String?) : UiNodeUi

    /** A table; [hidden] rows beyond the drawn ones are only counted. */
    data class Table(
        override val key: String,
        val columns: ImmutableList<ColumnUi>,
        val rows: ImmutableList<RowUi>,
        val emptyText: String?,
        val hidden: Int,
    ) : UiNodeUi

    /** A note that [count] more elements of a list are not drawn. */
    data class More(override val key: String, val count: Int) : UiNodeUi

    /** A button; [confirm] is asked first, [isBusy] while its action runs. */
    data class Button(
        override val key: String,
        val label: String,
        val style: ButtonStyleUi,
        val icon: String?,
        val isEnabled: Boolean,
        val isBusy: Boolean,
        val confirm: String?,
        val press: UiPress,
    ) : UiNodeUi

    /** A text input of the state key [stateKey]. */
    data class TextField(
        override val key: String,
        val stateKey: String,
        val value: String,
        val label: String?,
        val placeholder: String?,
        val isMultiline: Boolean,
    ) : UiNodeUi

    /** A switch of the state key [stateKey]. */
    data class Toggle(override val key: String, val stateKey: String, val label: String, val isChecked: Boolean) :
        UiNodeUi

    /** A choice of the state key [stateKey]. */
    data class Select(
        override val key: String,
        val stateKey: String,
        val label: String?,
        val options: ImmutableList<OptionUi>,
        val selected: String?,
    ) : UiNodeUi

    /** A platform widget writing the state key [stateKey]. */
    data class Native(
        override val key: String,
        val stateKey: String,
        val widget: NativeWidgetUi,
        val label: String?,
        val value: String,
        val extensions: ImmutableList<String>,
    ) : UiNodeUi

    /** An isolated web document; [data] is JSON delivered to the page. */
    data class Web(override val key: String, val html: String, val hasBridge: Boolean, val data: String?) : UiNodeUi
}

/** A modal dialog of a surface. */
@Immutable
internal data class UiModalUi(val name: String, val title: String, val body: UiNodeUi?, val isWide: Boolean)

/** An empty list of nodes. */
internal val NoNodes: ImmutableList<UiNodeUi> = persistentListOf()
