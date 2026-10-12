package io.aequicor.heartbeat.feature.serviceasui.impl.presentation

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiButtonStyle
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiGap
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiNativeWidget
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiNode
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTemplate
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTextStyle
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiTone
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.parseUiPath
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ButtonStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ColumnUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.GapUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.OptionUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.PairUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.RowUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.ToneUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiModalUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiPress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Turns a spec and its current values into resolved UI models: bindings become values, lists and tables expand over
 * their arrays (at most [UiSpecLimits.LIST_ITEMS] elements), conditions pick their branch, inputs show local state.
 * Pure; parsed templates are cached for the lifetime of the evaluator.
 */
internal class UiEvaluator(private val spec: UiSpec) {
    private val templates = HashMap<String, UiTemplate?>()

    /** The body drawn with [values]; null when a condition hides it. */
    @HighFrequency
    fun body(values: UiValues): UiNodeUi? = Pass(values).node(spec.body, "b", UiScope())

    /** The modal [name] drawn with [values], whose `args` are the modal's arguments; null when it is unknown. */
    @HighFrequency
    fun modal(name: String, values: UiValues): UiModalUi? {
        val modal = spec.modals[name] ?: return null
        val pass = Pass(values)
        return UiModalUi(
            name,
            pass.text(modal.title, UiScope()),
            pass.node(modal.body, "m.$name", UiScope()),
            modal.isWide,
        )
    }

    /** Bindings over [values], for actions evaluated when they run. */
    fun bindings(values: UiValues): UiBindings = UiBindings(values, templates)

    private inner class Pass(private val values: UiValues) {
        private val bindings = UiBindings(values, templates)

        fun text(text: String, scope: UiScope): String = bindings.text(text, scope)

        fun node(node: UiNode, key: String, scope: UiScope): UiNodeUi? = when (node) {
            is UiNode.Layout -> layout(node, key, scope)
            is UiNode.Content -> content(node, key, scope)
            is UiNode.Structure -> structure(node, key, scope)
            is UiNode.Input -> input(node, key, scope)
        }

        private fun layout(node: UiNode.Layout, key: String, scope: UiScope): UiNodeUi = when (node) {
            is UiNode.Column -> UiNodeUi.Column(key, children(node.children, key, scope), node.gap.ui())

            is UiNode.Row -> UiNodeUi.Row(key, children(node.children, key, scope), node.gap.ui(), node.isWrapped)

            is UiNode.Card -> UiNodeUi.Card(
                key,
                node.title?.let { text(it, scope) },
                children(node.children, key, scope),
            )

            is UiNode.Section -> UiNodeUi.Section(
                key,
                text(node.title, scope),
                node.description?.let { text(it, scope) },
                children(node.children, key, scope),
            )

            is UiNode.Divider -> UiNodeUi.Divider(key)

            is UiNode.Spacer -> UiNodeUi.Spacer(key, node.size.ui())
        }

        private fun content(node: UiNode.Content, key: String, scope: UiScope): UiNodeUi = when (node) {
            is UiNode.Text -> UiNodeUi.Text(key, text(node.text, scope), node.style.ui(), node.tone.ui())

            is UiNode.Markdown -> UiNodeUi.Markdown(key, text(node.text, scope))

            is UiNode.Badge -> UiNodeUi.Badge(key, text(node.text, scope), node.tone.ui())

            is UiNode.Progress -> UiNodeUi.Progress(
                key,
                node.value?.let { progress(it, scope) },
                node.label?.let { text(it, scope) },
            )

            is UiNode.KeyValue -> UiNodeUi.KeyValue(
                key,
                node.items.map { PairUi(text(it.key, scope), text(it.value, scope)) }.toImmutableList(),
            )

            is UiNode.Empty -> UiNodeUi.Empty(key, text(node.title, scope), node.description?.let { text(it, scope) })

            is UiNode.Diagram -> UiNodeUi.Diagram(key, text(node.source, scope), node.title?.let { text(it, scope) })

            is UiNode.Web -> UiNodeUi.Web(
                key,
                node.html,
                node.hasBridge,
                node.data?.let { bindings.value(it, scope).toString() },
            )
        }

        private fun structure(node: UiNode.Structure, key: String, scope: UiScope): UiNodeUi? = when (node) {
            is UiNode.Items -> items(node, key, scope)

            is UiNode.Table -> table(node, key, scope)

            is UiNode.If -> if (bindings.isTrue(node.condition, scope)) {
                node(node.then, "$key.then", scope)
            } else {
                node.otherwise?.let { node(it, "$key.else", scope) }
            }
        }

        private fun input(node: UiNode.Input, key: String, scope: UiScope): UiNodeUi = when (node) {
            is UiNode.Button -> button(node, key, scope)

            is UiNode.TextField -> UiNodeUi.TextField(
                key,
                node.bind,
                values.state[node.bind].text(),
                node.label?.let { text(it, scope) },
                node.placeholder?.let { text(it, scope) },
                node.isMultiline,
            )

            is UiNode.Toggle -> UiNodeUi.Toggle(
                key,
                node.bind,
                text(node.label, scope),
                values.state[node.bind].isTruthy(),
            )

            is UiNode.Select -> UiNodeUi.Select(
                key,
                node.bind,
                node.label?.let { text(it, scope) },
                node.options.map { OptionUi(it.value, text(it.label, scope)) }.toImmutableList(),
                values.state[node.bind]?.text(),
            )

            is UiNode.Native -> UiNodeUi.Native(
                key,
                node.bind,
                node.widget.ui(),
                node.label?.let { text(it, scope) },
                values.state[node.bind].text(),
                node.extensions.toImmutableList(),
            )
        }

        private fun children(nodes: List<UiNode>, key: String, scope: UiScope): ImmutableList<UiNodeUi> =
            nodes.mapIndexedNotNull { index, child -> node(child, "$key.$index", scope) }.toImmutableList()

        private fun items(node: UiNode.Items, key: String, scope: UiScope): UiNodeUi? {
            val array = bindings.value(node.items, scope) as? JsonArray ?: JsonArray(emptyList())
            if (array.isEmpty()) return node.empty?.let { node(it, "$key.empty", scope) }
            val shown = array.take(UiSpecLimits.LIST_ITEMS)
            val keys = elementKeys(shown, node.key)
            val elements = shown.mapIndexedNotNull { index, item ->
                node(node.item, "$key[${keys[index]}]", UiScope(item, index))
            }
            val more = (array.size - shown.size).takeIf { it > 0 }?.let { UiNodeUi.More("$key.more", it) }
            return UiNodeUi.Column(key, (elements + listOfNotNull(more)).toImmutableList(), GapUi.Small)
        }

        private fun table(node: UiNode.Table, key: String, scope: UiScope): UiNodeUi {
            val array = bindings.value(node.rows, scope) as? JsonArray ?: JsonArray(emptyList())
            val shown = array.take(UiSpecLimits.LIST_ITEMS)
            val keys = elementKeys(shown, node.key)
            val rows = shown.mapIndexed { index, row ->
                val rowScope = UiScope(row, index)
                val origin = "$key[${keys[index]}]"
                RowUi(
                    keys[index],
                    node.columns.map { text(it.value, rowScope) }.toImmutableList(),
                    node.onRowClick?.let { UiPress(origin, it, rowScope) },
                    isBusy = origin in values.busy,
                )
            }
            return UiNodeUi.Table(
                key,
                node.columns.map { ColumnUi(text(it.title, scope), it.weight) }.toImmutableList(),
                rows.toImmutableList(),
                node.empty?.let { text(it, scope) },
                hidden = array.size - shown.size,
            )
        }

        private fun button(node: UiNode.Button, key: String, scope: UiScope): UiNodeUi.Button {
            val origin = node.id ?: key
            return UiNodeUi.Button(
                key = key,
                label = text(node.label, scope),
                style = node.style.ui(),
                icon = node.icon,
                isEnabled = node.enabledWhen?.let { bindings.isTrue(it, scope) } ?: true,
                isBusy = origin in values.busy,
                confirm = node.confirm?.let { text(it, scope) },
                press = UiPress(origin, node.action, scope),
            )
        }

        /** A share in 0..1; a value that is not a finite number (NaN from an owner's division) is indeterminate. */
        private fun progress(value: String, scope: UiScope): Float? {
            val number = value.toDoubleOrNull() ?: (bindings.value(value, scope) as? JsonPrimitive)?.doubleOrNull
            return number?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat()
        }

        /** Keys of the elements from [key] when they are present and unique, otherwise their positions. */
        private fun elementKeys(elements: List<JsonElement>, key: String?): List<String> {
            val path = key?.let { parseUiPath("item.$it") }
            val keys = path?.let {
                elements.mapIndexed { index, item ->
                    bindings.resolve(
                        it,
                        UiScope(item, index),
                    ).text()
                }
            }
            return if (keys != null && keys.none { it.isEmpty() } && keys.distinct().size == keys.size) {
                keys
            } else {
                elements.indices.map { it.toString() }
            }
        }
    }
}

private fun UiGap.ui(): GapUi = when (this) {
    UiGap.None -> GapUi.None
    UiGap.Small -> GapUi.Small
    UiGap.Medium -> GapUi.Medium
    UiGap.Large -> GapUi.Large
}

private fun UiTone.ui(): ToneUi = when (this) {
    UiTone.Neutral -> ToneUi.Neutral
    UiTone.Brand -> ToneUi.Brand
    UiTone.Success -> ToneUi.Success
    UiTone.Warning -> ToneUi.Warning
    UiTone.Danger -> ToneUi.Danger
}

private fun UiTextStyle.ui(): TextStyleUi = when (this) {
    UiTextStyle.Body -> TextStyleUi.Body
    UiTextStyle.Caption -> TextStyleUi.Caption
    UiTextStyle.Title -> TextStyleUi.Title
    UiTextStyle.Heading -> TextStyleUi.Heading
    UiTextStyle.Code -> TextStyleUi.Code
}

private fun UiButtonStyle.ui(): ButtonStyleUi = when (this) {
    UiButtonStyle.Primary -> ButtonStyleUi.Primary
    UiButtonStyle.Secondary -> ButtonStyleUi.Secondary
    UiButtonStyle.Ghost -> ButtonStyleUi.Ghost
    UiButtonStyle.Danger -> ButtonStyleUi.Danger
}

private fun UiNativeWidget.ui(): NativeWidgetUi = when (this) {
    UiNativeWidget.Date -> NativeWidgetUi.Date
    UiNativeWidget.File -> NativeWidgetUi.File
    UiNativeWidget.Folder -> NativeWidgetUi.Folder
}
