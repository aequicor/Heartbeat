package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Tool delivery state, updated by the agent integration rather than inferred from its payload. */
public enum class HbToolStatus { Running, Complete, Error }

/** Localized disclosure actions and status descriptions. */
@Immutable
public data class HbToolLabels(
    val expand: String = "Expand result",
    val collapse: String = "Collapse result",
    val running: String = "Running",
    val complete: String = "Complete",
    val error: String = "Failed",
    val details: String = "Details",
    val console: String = "Console",
    val diff: String = "Code changes",
    val copyFilePath: String = "Copy file path",
    val filePathCopied: String = "Path copied",
    val unknownFile: String = "Code changes",
)

/** Typed tool payloads; console and unified diff content are always treated as literal text. */
@Immutable
public sealed interface HbToolBlock {
    public val id: String

    /** Rich prose rendered through the same parser as agent messages. */
    @Immutable
    public data class Markdown(override val id: String, val source: String) : HbToolBlock

    /** Literal console output, prepared as bounded rows with optional horizontal scrolling. */
    @Immutable
    public data class Console(override val id: String, val text: String) : HbToolBlock

    /** Unified diff with semantic added/removed colors and original +/- markers. */
    @Immutable
    public data class Diff(override val id: String, val text: String) : HbToolBlock
}

/** Stable display identity preserves disclosure state as streamed results grow or leave the viewport. */
@Immutable
public data class HbToolCall(
    val id: String,
    val title: String,
    val status: HbToolStatus = HbToolStatus.Complete,
    val summary: String = "",
    val blocks: ImmutableList<HbToolBlock> = persistentListOf(),
) {
    init {
        require(id.isNotBlank()) { "A tool call needs a stable non-blank id." }
        require(blocks.all { it.id.isNotBlank() }) { "Tool block ids must not be blank." }
        require(blocks.map { it.id }.toSet().size == blocks.size) { "Tool block ids must be unique within a call." }
    }
}
