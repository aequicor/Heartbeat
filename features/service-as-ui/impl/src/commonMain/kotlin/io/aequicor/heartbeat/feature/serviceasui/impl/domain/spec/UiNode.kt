package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One element of a surface. Strings hold `{{path}}` bindings unless a field is documented as a key. */
@Serializable
internal sealed interface UiNode {
    /** Arranges other nodes. */
    @Serializable
    sealed interface Layout : UiNode

    /** Shows values. */
    @Serializable
    sealed interface Content : UiNode

    /** Repeats or chooses other nodes from data. */
    @Serializable
    sealed interface Structure : UiNode

    /** Takes user input or runs actions. */
    @Serializable
    sealed interface Input : UiNode

    /** Children stacked vertically. */
    @Serializable
    @SerialName("column")
    data class Column(val children: List<UiNode>, val gap: UiGap = UiGap.Medium) : Layout

    /** Children side by side, wrapping to new lines when [isWrapped]. */
    @Serializable
    @SerialName("row")
    data class Row(
        val children: List<UiNode>,
        val gap: UiGap = UiGap.Medium,
        @SerialName("wrap") val isWrapped: Boolean = false,
    ) : Layout

    /** A framed group with an optional title. */
    @Serializable
    @SerialName("card")
    data class Card(val children: List<UiNode>, val title: String? = null) : Layout

    /** A titled group without a frame. */
    @Serializable
    @SerialName("section")
    data class Section(val title: String, val children: List<UiNode>, val description: String? = null) : Layout

    /** A thin separator. */
    @Serializable
    @SerialName("divider")
    data object Divider : Layout

    /** Empty space of [size]. */
    @Serializable
    @SerialName("spacer")
    data class Spacer(val size: UiGap = UiGap.Medium) : Layout

    /** Plain text. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String, val style: UiTextStyle = UiTextStyle.Body, val tone: UiTone = UiTone.Neutral) :
        Content

    /** Markdown text, including ```plantuml fences on hosts that draw diagrams. */
    @Serializable
    @SerialName("markdown")
    data class Markdown(val text: String) : Content

    /** A short colored label. */
    @Serializable
    @SerialName("badge")
    data class Badge(val text: String, val tone: UiTone = UiTone.Neutral) : Content

    /** A progress bar of [value] in 0..1 (a binding or a number), indeterminate without it. */
    @Serializable
    @SerialName("progress")
    data class Progress(val value: String? = null, val label: String? = null) : Content

    /** Pairs of a key and a value. */
    @Serializable
    @SerialName("keyValue")
    data class KeyValue(val items: List<UiKeyValueItem>) : Content

    /** An empty-state message. */
    @Serializable
    @SerialName("empty")
    data class Empty(val title: String, val description: String? = null) : Content

    /** A PlantUML diagram drawn where the host supports it, otherwise its source. */
    @Serializable
    @SerialName("diagram")
    data class Diagram(val source: String, val title: String? = null) : Content

    /**
     * [item] repeated for every element of the array bound by [items] (`{{data.prs}}`), with `item` and `index` in
     * scope; [key] (a path inside the item) keeps rows stable, [empty] shows when the array is empty.
     */
    @Serializable
    @SerialName("list")
    data class Items(val items: String, val item: UiNode, val key: String? = null, val empty: UiNode? = null) :
        Structure

    /** A table over the array bound by [rows]; cells are bindings of `item`, [onRowClick] runs with that row. */
    @Serializable
    @SerialName("table")
    data class Table(
        val rows: String,
        val columns: List<UiColumn>,
        val key: String? = null,
        val onRowClick: UiAction? = null,
        val empty: String? = null,
    ) : Structure

    /** [then] while the binding [condition] is truthy, otherwise [otherwise]. */
    @Serializable
    @SerialName("if")
    data class If(
        @SerialName("when") val condition: String,
        val then: UiNode,
        @SerialName("else") val otherwise: UiNode? = null,
    ) : Structure

    /**
     * Runs [action]; [enabledWhen] is a binding that must be truthy, [confirm] a question the user answers first.
     * [id] names the button in logs.
     */
    @Serializable
    @SerialName("button")
    data class Button(
        val label: String,
        val action: UiAction,
        val style: UiButtonStyle = UiButtonStyle.Secondary,
        val icon: String? = null,
        val enabledWhen: String? = null,
        val confirm: String? = null,
        val id: String? = null,
    ) : Input

    /** Edits the string in state key [bind]. */
    @Serializable
    @SerialName("textField")
    data class TextField(
        val bind: String,
        val label: String? = null,
        val placeholder: String? = null,
        @SerialName("multiline") val isMultiline: Boolean = false,
    ) : Input

    /** Switches the boolean in state key [bind]. */
    @Serializable
    @SerialName("toggle")
    data class Toggle(val bind: String, val label: String) : Input

    /** Chooses one of [options] into state key [bind]. */
    @Serializable
    @SerialName("select")
    data class Select(val bind: String, val options: List<UiOption>, val label: String? = null) : Input

    /** A platform [widget] writing its value into state key [bind]; [extensions] limit chosen files. */
    @Serializable
    @SerialName("native")
    data class Native(
        val widget: UiNativeWidget,
        val bind: String,
        val label: String? = null,
        val extensions: List<String> = emptyList(),
    ) : Input

    /**
     * An isolated web document without network access (desktop panels and modals). With [hasBridge] its script calls
     * owner methods through `heartbeat.call`; [data] is a binding delivered to the page.
     */
    @Serializable
    @SerialName("web")
    data class Web(
        val html: String,
        @SerialName("bridge") val hasBridge: Boolean = false,
        val data: String? = null,
    ) : Content
}

/** One pair of a [UiNode.KeyValue]. */
@Serializable
internal data class UiKeyValueItem(val key: String, val value: String)

/** One column of a [UiNode.Table]: [value] is a binding of `item`; [weight] is its share of the width. */
@Serializable
internal data class UiColumn(val title: String, val value: String, val weight: Float = 1f)

/** One choice of a [UiNode.Select]. */
@Serializable
internal data class UiOption(val value: String, val label: String)

/** Direct children of this node, in drawing order. */
internal val UiNode.children: List<UiNode>
    get() = when (this) {
        is UiNode.Column -> children

        is UiNode.Row -> children

        is UiNode.Card -> children

        is UiNode.Section -> children

        is UiNode.Items -> listOfNotNull(item, empty)

        is UiNode.If -> listOfNotNull(then, otherwise)

        is UiNode.Divider, is UiNode.Spacer, is UiNode.Text, is UiNode.Markdown, is UiNode.Badge,
        is UiNode.Progress, is UiNode.KeyValue, is UiNode.Empty, is UiNode.Diagram, is UiNode.Table,
        is UiNode.Button, is UiNode.TextField, is UiNode.Toggle, is UiNode.Select, is UiNode.Native,
        is UiNode.Web,
        -> emptyList()
    }

/** Every body of [this] spec: the root and the modals. */
internal val UiSpec.bodies: List<UiNode> get() = listOf(body) + modals.values.map { it.body }
