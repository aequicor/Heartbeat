package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessEntry
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessRejection
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.isHarnessInputSchema
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.CodeCheck
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCodeChecks
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessLibraryClient
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.LibraryOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeDiagnostic
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessDiagnosticSeverity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import kotlin.time.Clock

/** Unsaved editor texts of one item and the revision they were based on; never logged. */
internal data class EditorDraft(val text: String, val description: String, val input: String, val baseRevision: Long) {
    override fun toString(): String = "EditorDraft(revision=$baseRevision, ***)"
}

/** Profile-lifetime cache of unsaved editor texts: leaving the editor keeps them until saved or discarded. */
@SingleIn(ProfileScope::class)
@Inject
internal class HarnessDrafts {
    private val drafts = MutableStateFlow<Map<String, EditorDraft>>(emptyMap())

    fun get(key: String): EditorDraft? = drafts.value[key]

    @HighFrequency
    fun put(key: String, draft: EditorDraft) {
        drafts.update { it + (key to draft) }
        log.v { "Item draft kept" }
    }

    fun remove(key: String) {
        drafts.update { it - key }
        log.v { "Item draft dropped" }
    }
}

/** What the item editor saves through: the library, code checks and the profile draft cache. */
internal data class HarnessItemEditing(
    val library: HarnessLibraryClient,
    val checks: HarnessCodeChecks,
    val drafts: HarnessDrafts,
)

/** Why the last save did not apply. */
internal enum class ItemErrorUi { SaveFailed, InvalidInput, TooLong, CompileTimedOut }

/** The item editor: text or code, its metadata and the last compilation diagnostics. */
@Immutable
internal data class HarnessItemState(
    val phase: PhaseUi = PhaseUi.Loading,
    val harnessName: String = "",
    val name: String = "",
    val kind: ItemKindUi = ItemKindUi.Instruction,
    val text: String = "",
    val description: String = "",
    val input: String = "",
    val isReadOnly: Boolean = false,
    val diagnostics: ImmutableList<DiagnosticUi> = persistentListOf(),
    val isDirty: Boolean = false,
    val isDraftRestored: Boolean = false,
    val isSaving: Boolean = false,
    val isConflict: Boolean = false,
    val error: ItemErrorUi? = null,
) : MVIState {
    val hasDescription: Boolean get() = kind != ItemKindUi.Instruction
    val hasInput: Boolean get() = kind == ItemKindUi.Workflow
    val isCode: Boolean get() = kind == ItemKindUi.Script || kind == ItemKindUi.Workflow
}

/** Controls of the item editor. */
internal sealed interface HarnessItemIntent : MVIIntent {
    data class ChangeText(val text: String) : HarnessItemIntent
    data class ChangeDescription(val description: String) : HarnessItemIntent
    data class ChangeInput(val input: String) : HarnessItemIntent
    data object Save : HarnessItemIntent

    /** Drops the unsaved texts and shows the committed item. */
    data object Discard : HarnessItemIntent

    /** Saves over a newer revision after a conflict. */
    data object Overwrite : HarnessItemIntent
    data object DismissError : HarnessItemIntent
}

/** Reserved contract for one-off actions. */
internal sealed interface HarnessItemAction : MVIAction

private typealias ItemPipeline = PipelineContext<HarnessItemState, HarnessItemIntent, HarnessItemAction>

/**
 * User edits need no approval. Code is compiled first and nothing is saved while it fails; a save targets the
 * revision the edit started from, so a concurrent agent edit becomes a conflict the user resolves.
 */
internal class HarnessItemModel(
    harness: String,
    item: String,
    private val machine: HarnessMachine,
    editing: HarnessItemEditing,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val library = editing.library
    private val checks = editing.checks
    private val drafts = editing.drafts
    private val harnessId = HarnessId(harness)
    private val itemId = ItemId(item)
    private val draftKey = Json.encodeToString(listOf(harness, item))

    val store = factory.create<HarnessItemState, HarnessItemIntent, HarnessItemAction>(
        "HarnessItem",
        HarnessItemState(isDraftRestored = drafts.get(draftKey) != null).reflectItem(machine.state.value),
        onError = { this },
    ) {
        reflect(machine) { reflectItem(it) }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ItemPipeline.handle(intent: HarnessItemIntent) {
        when (intent) {
            is HarnessItemIntent.ChangeText -> edit { it.copy(text = intent.text) }

            is HarnessItemIntent.ChangeDescription -> edit { it.copy(description = intent.description) }

            is HarnessItemIntent.ChangeInput -> edit { it.copy(input = intent.input) }

            HarnessItemIntent.Discard -> {
                drafts.remove(draftKey)
                updateState {
                    copy(isDirty = false, isDraftRestored = false, isConflict = false, diagnostics = persistentListOf())
                        .reflectItem(machine.state.value)
                }
            }

            HarnessItemIntent.Save -> save(isOverwrite = false)

            HarnessItemIntent.Overwrite -> save(isOverwrite = true)

            HarnessItemIntent.DismissError -> updateState { copy(error = null) }
        }
    }

    /** Called on every keystroke: the draft is kept after the state update, and old diagnostics no longer apply. */
    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    @HighFrequency
    private suspend fun ItemPipeline.edit(change: (EditorDraft) -> EditorDraft) {
        var kept: EditorDraft? = null
        updateState {
            if (isReadOnly || phase != PhaseUi.Ready) return@updateState this
            val next = change(
                drafts.get(draftKey) ?: EditorDraft(text, description, input, entry()?.harness?.revision ?: 0),
            )
            kept = next
            copy(
                text = next.text,
                description = next.description,
                input = next.input,
                isDirty = true,
                error = null,
                diagnostics = persistentListOf(),
            )
        }
        kept?.let { drafts.put(draftKey, it) }
    }

    /** Marks the save in the state, then compiles and submits outside the state transaction, so typing continues. */
    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun ItemPipeline.save(isOverwrite: Boolean) {
        var shown: HarnessItemState? = null
        updateState {
            if (isReadOnly || isSaving || phase != PhaseUi.Ready) return@updateState this
            shown = this
            copy(isSaving = true, error = null, isConflict = false)
        }
        val snapshot = shown ?: return
        log.i { "Save harness item overwrite=$isOverwrite" }
        val result = saved(snapshot, isOverwrite)
        // The result applies to the state current after the save, not to the snapshot it started from.
        updateState { result() }
    }

    private suspend fun saved(shown: HarnessItemState, isOverwrite: Boolean): ItemUpdate {
        val entry = entry()
        val stored = entry?.harness?.items?.firstOrNull { it.id == itemId } ?: return failed(ItemErrorUi.SaveFailed)
        val input = (if (shown.hasInput) parseInput(shown.input) else JsonObject(emptyMap()))
            ?: return failed(ItemErrorUi.InvalidInput)
        val edited = try {
            stored.edited(shown.text, shown.description, input)
        } catch (error: IllegalArgumentException) {
            log.w(IllegalArgumentException(error::class.simpleName)) { "Edited item exceeds its limits" }
            return failed(ItemErrorUi.TooLong)
        }
        val check = if (edited.isCodeItem()) {
            checks.check(harnessId, itemId, edited.codeKind(), shown.text, false)
        } else {
            null
        }
        val started = drafts.get(draftKey)?.baseRevision ?: entry.harness.revision
        val revision = if (isOverwrite) entry.harness.revision else started
        return when (check) {
            is CodeCheck.Failed -> {
                val diagnostics = check.diagnostics.toUi()
                ({ copy(isSaving = false, diagnostics = diagnostics) })
            }

            CodeCheck.TimedOut -> failed(ItemErrorUi.CompileTimedOut)

            CodeCheck.Unsupported -> ({ copy(isSaving = false, isReadOnly = true) })

            null, is CodeCheck.Compiled -> {
                val warnings = (check as? CodeCheck.Compiled)?.warnings.orEmpty().toUi()
                val submitted = submit(edited, revision)
                ({ submitted().copy(diagnostics = warnings) })
            }
        }
    }

    private suspend fun submit(item: HarnessItem, revision: Long): ItemUpdate {
        val intent = HarnessIntent.Public.Update(
            request(),
            harnessId,
            HarnessChange.PutItem(item),
            revision,
            HarnessAuthor.User,
            clock.now(),
        )
        return when (val outcome = library.submit(intent)) {
            is LibraryOutcome.Committed -> {
                drafts.remove(draftKey)
                ({ copy(isSaving = false, isDirty = false, isDraftRestored = false, isConflict = false) })
            }

            is LibraryOutcome.Rejected -> if (outcome.reason == HarnessRejection.Conflict) {
                { copy(isSaving = false, isConflict = true) }
            } else {
                failed(ItemErrorUi.SaveFailed)
            }

            else -> failed(ItemErrorUi.SaveFailed)
        }
    }

    private fun entry(): HarnessEntry? =
        (machine.state.value as? HarnessState.Ready)?.harnesses?.firstOrNull { it.harness.id == harnessId }

    private fun HarnessItemState.reflectItem(state: HarnessState): HarnessItemState {
        val ready = state as? HarnessState.Ready ?: return copy(phase = state.phase())
        val entry = ready.harnesses.firstOrNull { it.harness.id == harnessId }
        val item = entry?.harness?.items?.firstOrNull { it.id == itemId } ?: return copy(phase = PhaseUi.NotFound)
        val draft = drafts.get(draftKey)
        return copy(
            phase = PhaseUi.Ready,
            harnessName = entry.harness.name.value,
            name = item.name.value,
            kind = item.kindUi(),
            text = draft?.text ?: item.text(),
            description = draft?.description ?: item.summaryField(),
            input = draft?.input ?: (item as? HarnessItem.Workflow)?.input?.let(::prettyInput).orEmpty(),
            isReadOnly = item.isCode && !ready.isRuntimeAvailable,
            isDirty = draft != null,
        )
    }
}

private fun HarnessItem.text(): String = when (this) {
    is HarnessItem.Instruction -> text
    is HarnessItem.Skill -> body
    is HarnessItem.Template -> body
    is HarnessItem.Script -> source
    is HarnessItem.Workflow -> source
}

private fun HarnessItem.summaryField(): String = if (this is HarnessItem.Instruction) "" else summary()

private fun HarnessItem.isCodeItem(): Boolean = this is HarnessItem.Script || this is HarnessItem.Workflow

private fun HarnessItem.codeKind(): HarnessCodeKind =
    if (this is HarnessItem.Script) HarnessCodeKind.Script else HarnessCodeKind.Workflow

/** Same identity, name and switch; template arguments follow the placeholders of the new body. */
private fun HarnessItem.edited(text: String, description: String, input: JsonObject): HarnessItem = when (this) {
    is HarnessItem.Instruction -> copy(text = text)

    is HarnessItem.Skill -> copy(body = text, description = description.trim())

    is HarnessItem.Template -> copy(
        body = text,
        description = description.trim(),
        arguments = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.filter { it.matches(ARGUMENT) }.toSet(),
    )

    is HarnessItem.Script -> copy(source = text, description = description.trim())

    is HarnessItem.Workflow -> copy(source = text, description = description.trim(), input = input)
}

private fun parseInput(text: String): JsonObject? = if (text.isBlank()) {
    JsonObject(emptyMap())
} else {
    try {
        (Json.parseToJsonElement(text) as? JsonObject)?.takeIf(::isHarnessInputSchema)
    } catch (error: SerializationException) {
        log.w(IllegalArgumentException(error::class.simpleName)) { "Workflow input schema is not JSON" }
        null
    }
}

private fun prettyInput(input: JsonObject): String =
    if (input.isEmpty()) "" else Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), input)

private fun List<HarnessCodeDiagnostic>.toUi(): ImmutableList<DiagnosticUi> = map {
    DiagnosticUi(it.line, it.column, it.message, it.severity == HarnessDiagnosticSeverity.Error)
}.toImmutableList()

private val log = Log.tag("HarnessHost")

/** A change applied to the editor state once a save finished. */
private typealias ItemUpdate = HarnessItemState.() -> HarnessItemState

private fun failed(error: ItemErrorUi): ItemUpdate = { copy(isSaving = false, error = error) }
private val PLACEHOLDER = Regex("\\{\\{([^{}]+)}}")
private val ARGUMENT = Regex("[a-z][a-z0-9_]{0,31}")
