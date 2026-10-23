package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a spec comes from: an approved library surface, or a card the agent showed without an owner. */
internal enum class UiSpecOrigin { Library, Inline }

/**
 * What a spec may use: the [placement] of a library surface (null for cards), the owner's [methods] (null while
 * unknown — calls are then only warnings) and the names of the owner's other [surfaces] (null while unknown).
 */
internal data class UiSpecRules(
    val origin: UiSpecOrigin,
    val placement: UiPlacement? = null,
    val methods: Set<String>? = null,
    val surfaces: Set<String>? = null,
)

/** Outcome of [checkUiSpec]. */
internal sealed interface UiSpecCheck {
    /** A usable [spec]; [warnings] are worth showing to the author. */
    data class Valid(val spec: UiSpec, val warnings: List<UiSpecDiagnostic>) : UiSpecCheck

    /** Not usable, for [diagnostics] (errors and warnings). */
    data class Invalid(val diagnostics: List<UiSpecDiagnostic>) : UiSpecCheck
}

/** Decodes [text] and validates it against [rules]. */
internal fun checkUiSpec(text: String, rules: UiSpecRules): UiSpecCheck = when (val decoding = decodeUiSpec(text)) {
    is UiSpecDecoding.Rejected -> UiSpecCheck.Invalid(decoding.diagnostics)

    is UiSpecDecoding.Decoded -> {
        val diagnostics = validateUiSpec(decoding.spec, rules)
        if (diagnostics.any { !it.isWarning }) {
            UiSpecCheck.Invalid(diagnostics)
        } else {
            UiSpecCheck.Valid(decoding.spec, diagnostics)
        }
    }
}

/**
 * Checks names, references, binding scopes, placement and origin rules of a decoded [spec]; returns at most
 * [UiSpecLimits.DIAGNOSTICS] findings.
 */
internal fun validateUiSpec(spec: UiSpec, rules: UiSpecRules): List<UiSpecDiagnostic> = SpecValidator(spec, rules).run()

/** Scope of bindings: whether `item`/`index` exist. */
private data class Scope(val hasItem: Boolean = false)

private class SpecValidator(private val spec: UiSpec, private val rules: UiSpecRules) {
    private val found = mutableListOf<UiSpecDiagnostic>()
    private var webs = 0
    private val isInline = rules.origin == UiSpecOrigin.Inline

    fun run(): List<UiSpecDiagnostic> {
        declarations()
        quickActions()
        spec.title?.let { template(it, "$ROOT.title", Scope()) }
        node(spec.body, "$ROOT.body", Scope())
        for ((name, modal) in spec.modals) {
            template(modal.title, "$ROOT.modals.$name.title", Scope())
            node(modal.body, "$ROOT.modals.$name.body", Scope())
        }
        return found.take(UiSpecLimits.DIAGNOSTICS)
    }

    private fun declarations() {
        if (spec.data.size > UiSpecLimits.SOURCES) report("$ROOT.data", UiSpecProblem.LimitExceeded, "sources")
        if (isInline && spec.data.isNotEmpty()) report("$ROOT.data", UiSpecProblem.OriginForbidden)
        for ((name, source) in spec.data) {
            name(name, "$ROOT.data.$name")
            method(source.method, "$ROOT.data.$name.method")
            value(source.args, "$ROOT.data.$name.args", Scope())
        }
        if (spec.modals.size > UiSpecLimits.MODALS) report("$ROOT.modals", UiSpecProblem.LimitExceeded, "modals")
        spec.modals.keys.forEach { name(it, "$ROOT.modals.$it") }
        spec.state.keys.forEach { name(it, "$ROOT.state.$it") }
        if (JsonObject(spec.state).toString().encodeToByteArray().size > UiSpecLimits.STATE_BYTES) {
            report("$ROOT.state", UiSpecProblem.LimitExceeded, "state size")
        }
    }

    /** Quick actions belong to the canonical app of a library owner and never see list elements. */
    private fun quickActions() {
        val path = "$ROOT.quickActions"
        if (spec.quickActions.isEmpty() || !areQuickActionsAllowed(path)) return
        if (spec.quickActions.size > UiSpecLimits.QUICK_ACTIONS) {
            report(path, UiSpecProblem.LimitExceeded, "${UiSpecLimits.QUICK_ACTIONS} quick actions")
        }
        val ids = HashSet<String>()
        spec.quickActions.forEachIndexed { index, quick ->
            val at = "$path[$index]"
            name(quick.id, "$at.id")
            if (!ids.add(quick.id)) report("$at.id", UiSpecProblem.InvalidValue, "a unique id")
            // The label names the button for screen readers and in the overflow menu.
            if (quick.label.isBlank()) report("$at.label", UiSpecProblem.InvalidValue, "a visible label")
            template(quick.label, "$at.label", Scope())
            quick.visibleWhen?.let { binding(it, "$at.visibleWhen", Scope()) }
            quick.enabledWhen?.let { binding(it, "$at.enabledWhen", Scope()) }
            quick.confirm?.let { template(it, "$at.confirm", Scope()) }
            quickAction(quick.action, "$at.action")
        }
    }

    private fun areQuickActionsAllowed(path: String): Boolean = when {
        isInline -> {
            report(path, UiSpecProblem.OriginForbidden)
            false
        }

        rules.placement != UiPlacement.Card -> {
            report(path, UiSpecProblem.QuickActionForbidden, "placement card")
            false
        }

        else -> true
    }

    /**
     * A quick action runs beside the surface's screen: only owner calls, reloads and local state, alone or in a
     * sequence. Prompts, navigation, links, files and `close` need the screen or would overwrite the chat draft.
     */
    private fun quickAction(action: UiAction, path: String) {
        when (action) {
            is UiAction.Call, is UiAction.Reload, is UiAction.SetState -> action(action, path, Scope())

            is UiAction.Steps -> {
                if (action.actions.isEmpty() || action.actions.size > UiSpecLimits.SEQUENCE) {
                    report("$path.actions", UiSpecProblem.LimitExceeded, "1..${UiSpecLimits.SEQUENCE} actions")
                }
                action.actions.forEachIndexed { index, step -> quickAction(step, "$path.actions[$index]") }
            }

            is UiAction.FocusCard, is UiAction.Open, is UiAction.Close, is UiAction.Prompt, is UiAction.OpenUrl,
            is UiAction.Copy, is UiAction.SaveFile, is UiAction.Reveal,
            -> report(path, UiSpecProblem.QuickActionForbidden, action.typeName())
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per node type keeps the catalog in one readable place.
    private fun node(node: UiNode, path: String, scope: Scope) {
        when (node) {
            is UiNode.Column -> children(node.children, path, scope)

            is UiNode.Row -> children(node.children, path, scope)

            is UiNode.Card -> {
                node.title?.let { template(it, "$path.title", scope) }
                children(node.children, path, scope)
            }

            is UiNode.Section -> {
                template(node.title, "$path.title", scope)
                node.description?.let { template(it, "$path.description", scope) }
                children(node.children, path, scope)
            }

            is UiNode.Divider, is UiNode.Spacer -> Unit

            is UiNode.Text -> template(node.text, "$path.text", scope)

            is UiNode.Markdown -> template(node.text, "$path.text", scope)

            is UiNode.Badge -> template(node.text, "$path.text", scope)

            is UiNode.Progress -> {
                node.value?.let { progress(it, "$path.value", scope) }
                node.label?.let { template(it, "$path.label", scope) }
            }

            is UiNode.KeyValue -> node.items.forEachIndexed { index, item ->
                template(item.key, "$path.items[$index].key", scope)
                template(item.value, "$path.items[$index].value", scope)
            }

            is UiNode.Empty -> {
                template(node.title, "$path.title", scope)
                node.description?.let { template(it, "$path.description", scope) }
            }

            is UiNode.Diagram -> diagram(node, path, scope)

            is UiNode.Items -> items(node, path, scope)

            is UiNode.Table -> table(node, path, scope)

            is UiNode.If -> {
                binding(node.condition, "$path.when", scope)
                node(node.then, "$path.then", scope)
                node.otherwise?.let { node(it, "$path.else", scope) }
            }

            is UiNode.Button -> button(node, path, scope)

            is UiNode.TextField -> {
                stateKey(node.bind, "$path.bind")
                node.label?.let { template(it, "$path.label", scope) }
                node.placeholder?.let { template(it, "$path.placeholder", scope) }
            }

            is UiNode.Toggle -> {
                stateKey(node.bind, "$path.bind")
                template(node.label, "$path.label", scope)
            }

            is UiNode.Select -> select(node, path, scope)

            is UiNode.Native -> native(node, path, scope)

            is UiNode.Web -> web(node, path, scope)
        }
    }

    private fun children(children: List<UiNode>, path: String, scope: Scope) =
        children.forEachIndexed { index, child -> node(child, "$path.children[$index]", scope) }

    private fun diagram(node: UiNode.Diagram, path: String, scope: Scope) {
        template(node.source, "$path.source", scope)
        node.title?.let { template(it, "$path.title", scope) }
        if (FENCE in node.source) report("$path.source", UiSpecProblem.DiagramFence)
    }

    private fun items(node: UiNode.Items, path: String, scope: Scope) {
        binding(node.items, "$path.items", scope)
        node.key?.let { itemKey(it, "$path.key") }
        node(node.item, "$path.item", scope.copy(hasItem = true))
        node.empty?.let { node(it, "$path.empty", scope) }
    }

    private fun table(node: UiNode.Table, path: String, scope: Scope) {
        binding(node.rows, "$path.rows", scope)
        if (node.columns.isEmpty() || node.columns.size > UiSpecLimits.COLUMNS) {
            report("$path.columns", UiSpecProblem.LimitExceeded, "1..${UiSpecLimits.COLUMNS} columns")
        }
        node.columns.forEachIndexed { index, column ->
            template(column.title, "$path.columns[$index].title", scope)
            template(column.value, "$path.columns[$index].value", scope.copy(hasItem = true))
            if (column.weight <= 0f) report("$path.columns[$index].weight", UiSpecProblem.InvalidValue, "> 0")
        }
        node.key?.let { itemKey(it, "$path.key") }
        node.onRowClick?.let { action(it, "$path.onRowClick", scope.copy(hasItem = true)) }
        node.empty?.let { template(it, "$path.empty", scope) }
    }

    private fun button(node: UiNode.Button, path: String, scope: Scope) {
        // The label names the button for screen readers and in the tooltip of an icon button.
        if (node.label.isBlank()) report("$path.label", UiSpecProblem.InvalidValue, "a visible label")
        template(node.label, "$path.label", scope)
        action(node.action, "$path.action", scope)
        node.enabledWhen?.let { binding(it, "$path.enabledWhen", scope) }
        node.confirm?.let { template(it, "$path.confirm", scope) }
        node.id?.let { name(it, "$path.id") }
    }

    private fun select(node: UiNode.Select, path: String, scope: Scope) {
        stateKey(node.bind, "$path.bind")
        node.label?.let { template(it, "$path.label", scope) }
        val values = node.options.map { it.value }
        if (values.isEmpty() || values.size > UiSpecLimits.OPTIONS || values.distinct().size != values.size) {
            report("$path.options", UiSpecProblem.InvalidValue, "1..${UiSpecLimits.OPTIONS} distinct values")
        }
        node.options.forEachIndexed { index, option -> template(option.label, "$path.options[$index].label", scope) }
    }

    private fun native(node: UiNode.Native, path: String, scope: Scope) {
        stateKey(node.bind, "$path.bind")
        node.label?.let { template(it, "$path.label", scope) }
        val isFile = node.widget == UiNativeWidget.File
        if (node.extensions.isNotEmpty() && (!isFile || node.extensions.any { !it.matches(Extension) })) {
            report("$path.extensions", UiSpecProblem.InvalidValue, "file extensions such as \"json\"")
        }
    }

    private fun web(node: UiNode.Web, path: String, scope: Scope) {
        webs++
        if (webs > 1) report(path, UiSpecProblem.LimitExceeded, "one web document per surface")
        if (node.html.encodeToByteArray().size > UiSpecLimits.HTML_BYTES) {
            report("$path.html", UiSpecProblem.LimitExceeded, "${UiSpecLimits.HTML_BYTES} bytes")
        }
        if (node.hasBridge && isInline) report("$path.bridge", UiSpecProblem.OriginForbidden)
        node.data?.let { binding(it, "$path.data", scope) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action type keeps the catalog in one readable place.
    private fun action(action: UiAction, path: String, scope: Scope) {
        when (action) {
            is UiAction.Call -> {
                forbiddenInline(path)
                method(action.method, "$path.method")
                value(action.args, "$path.args", scope)
                action.into?.let { stateKey(it, "$path.into") }
            }

            is UiAction.FocusCard -> focusCard(action, path)

            is UiAction.Reload -> {
                forbiddenInline(path)
                if (action.source !in spec.data) report("$path.source", UiSpecProblem.UnknownSource, action.source)
            }

            is UiAction.SetState -> {
                stateKey(action.key, "$path.key")
                value(action.value, "$path.value", scope)
            }

            is UiAction.Open -> open(action, path, scope)

            is UiAction.Close -> Unit

            is UiAction.Prompt -> {
                template(action.text, "$path.text", scope)
                if (action.isSubmitted && isInline) report("$path.submit", UiSpecProblem.OriginForbidden)
            }

            is UiAction.OpenUrl -> url(action.url, "$path.url", scope)

            is UiAction.Copy -> template(action.text, "$path.text", scope)

            is UiAction.SaveFile -> {
                template(action.text, "$path.text", scope)
                if (!action.name.matches(FileName)) report("$path.name", UiSpecProblem.InvalidValue, "a file name")
                if (action.extensions.any { !it.matches(Extension) }) {
                    report("$path.extensions", UiSpecProblem.InvalidValue, "file extensions such as \"json\"")
                }
            }

            is UiAction.Reveal -> {
                forbiddenInline(path)
                template(action.path, "$path.path", scope)
            }

            is UiAction.Steps -> {
                if (action.actions.isEmpty() || action.actions.size > UiSpecLimits.SEQUENCE) {
                    report("$path.actions", UiSpecProblem.LimitExceeded, "1..${UiSpecLimits.SEQUENCE} actions")
                }
                action.actions.forEachIndexed { index, step -> action(step, "$path.actions[$index]", scope) }
            }
        }
    }

    private fun focusCard(action: UiAction.FocusCard, path: String) {
        forbiddenInline(path)
        name(action.surface, "$path.surface")
        if (rules.surfaces?.contains(action.surface) == false) {
            report("$path.surface", UiSpecProblem.UnknownSurface, action.surface)
        }
    }

    private fun open(action: UiAction.Open, path: String, scope: Scope) {
        val modal = action.modal
        val surface = action.surface
        when {
            (modal == null) == (surface == null) ->
                report(path, UiSpecProblem.InvalidValue, "exactly one of modal and surface")

            modal != null && modal !in spec.modals -> report("$path.modal", UiSpecProblem.UnknownModal, modal)

            modal != null && action.presentation == UiPresentation.Panel ->
                report("$path.presentation", UiSpecProblem.InvalidValue, "modals open as modals")

            surface != null && isInline -> report("$path.surface", UiSpecProblem.OriginForbidden)

            surface != null && rules.surfaces?.contains(surface) == false ->
                report("$path.surface", UiSpecProblem.UnknownSurface, surface)
        }
        value(action.args, "$path.args", scope)
    }

    private fun forbiddenInline(path: String) {
        if (isInline) report(path, UiSpecProblem.OriginForbidden)
    }

    private fun method(name: String, path: String) {
        val methods = rules.methods
        when {
            methods == null -> report(path, UiSpecProblem.UnknownMethod, name, isWarning = true)
            name !in methods -> report(path, UiSpecProblem.UnknownMethod, name)
        }
    }

    private fun url(text: String, path: String, scope: Scope) {
        val template = template(text, path, scope) ?: return
        if (template.paths.isEmpty() && !text.startsWith("https://") && !text.startsWith("http://")) {
            report(path, UiSpecProblem.InvalidUrl)
        }
    }

    private fun progress(text: String, path: String, scope: Scope) {
        val number = text.toDoubleOrNull()
        if (number == null) binding(text, path, scope)
        if (number != null && number !in 0.0..1.0) report(path, UiSpecProblem.InvalidValue, "0..1")
    }

    /** Strings inside [element] are templates; nested values are walked. */
    private fun value(element: JsonElement, path: String, scope: Scope) {
        when (element) {
            is JsonObject -> element.forEach { (key, nested) -> value(nested, "$path.$key", scope) }
            is JsonArray -> element.forEachIndexed { index, nested -> value(nested, "$path[$index]", scope) }
            is JsonPrimitive -> if (element.isString) template(element.content, path, scope)
        }
    }

    private fun binding(text: String, path: String, scope: Scope) {
        val template = template(text, path, scope) ?: return
        if (template.single == null) report(path, UiSpecProblem.InvalidBinding, "needs exactly one {{binding}}")
    }

    private fun template(text: String, path: String, scope: Scope): UiTemplate? =
        when (val parse = parseUiTemplate(text)) {
            is UiTemplateParse.Invalid -> {
                report(path, UiSpecProblem.InvalidBinding, parse.reason)
                null
            }

            is UiTemplateParse.Parsed -> parse.template.also { template ->
                template.paths.forEach {
                    checkPath(
                        it,
                        path,
                        scope,
                    )
                }
            }
        }

    private fun checkPath(path: UiPath, at: String, scope: Scope) {
        val head = path.head
        when (path.root) {
            UiPathRoot.Data -> if (head !in spec.data) report(at, UiSpecProblem.UnknownSource, "$path")

            UiPathRoot.State -> if (head !in spec.state) report(at, UiSpecProblem.UnknownStateKey, "$path")

            UiPathRoot.Item, UiPathRoot.Index -> element(path, at, scope)

            UiPathRoot.Status -> status(path, at)

            UiPathRoot.Args -> Unit

            UiPathRoot.Context -> if (head !in ContextKeys || path.segments.size != 1) {
                report(at, UiSpecProblem.InvalidBinding, "context.platform or context.hasSession")
            }
        }
    }

    /** `item` and `index` need a list or table around them; `index` has no fields. */
    private fun element(path: UiPath, at: String, scope: Scope) {
        when {
            !scope.hasItem -> report(at, UiSpecProblem.OutOfScope, "$path")

            path.root == UiPathRoot.Index && path.segments.isNotEmpty() ->
                report(at, UiSpecProblem.InvalidBinding, "$path")
        }
    }

    private fun status(path: UiPath, at: String) {
        val source = path.head
        val field = (path.segments.getOrNull(1) as? UiPathSegment.Key)?.name
        when {
            source == null || source !in spec.data -> report(at, UiSpecProblem.UnknownSource, "$path")

            field !in StatusKeys || path.segments.size != 2 ->
                report(at, UiSpecProblem.InvalidBinding, "status.<source>.loading|failed|ready|error")
        }
    }

    private fun stateKey(key: String, path: String) {
        if (key !in spec.state) report(path, UiSpecProblem.UnknownStateKey, key)
    }

    private fun itemKey(key: String, path: String) {
        if (parseUiPath("item.$key") == null) report(path, UiSpecProblem.InvalidValue, "a path inside the item")
    }

    private fun name(name: String, path: String) {
        if (!name.matches(Name)) report(path, UiSpecProblem.InvalidName, name)
    }

    private fun report(path: String, problem: UiSpecProblem, detail: String? = null, isWarning: Boolean = false) {
        found += UiSpecDiagnostic(path, problem, detail, isWarning)
    }
}

/** The `type` of an action in the spec: its class name with a lower-case first letter. */
private fun UiAction.typeName(): String? = this::class.simpleName?.replaceFirstChar { it.lowercase() }

private const val ROOT = "$"
private const val FENCE = "```"
private val Name = Regex("[A-Za-z_][A-Za-z0-9_-]{0,31}")
private val Extension = Regex("[A-Za-z0-9]{1,16}")
private val FileName = Regex("[^/\\\\:*?\"<>|]{1,128}")
private val ContextKeys = setOf("platform", "hasSession")
private val StatusKeys = setOf("loading", "failed", "ready", "error")
