package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptBudget
import io.aequicor.heartbeat.feature.aiengine.facade.api.brief
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessItem
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryMarker

/**
 * Pure content projection of a host-authorized active snapshot. It does not resolve sessions or grant access.
 * Bare names must identify exactly one enabled item across that snapshot, even across different content kinds.
 * Code and skill/template bodies never enter the context index; explicit reads return only the requested body.
 */
internal class HarnessContent(active: List<Harness>) {
    private val harnesses = active.filter { it.isEnabled }.sortedBy { it.name.value }
    private val entries = harnesses.flatMap { harness ->
        harness.items.asSequence().filter { it.isEnabled }.sortedBy { it.name.value }
            .map { ContentEntry(harness, it) }.toList()
    }

    init {
        require(harnesses.size <= HarnessLimits.ACTIVE_PER_SESSION) { "Too many active harnesses" }
        require(harnesses.map { it.id }.distinct().size == harnesses.size) { "Duplicate active harness identity" }
        require(harnesses.map { it.name }.distinct().size == harnesses.size) { "Duplicate active harness name" }
    }

    fun skill(name: String): String = (resolve(name).item as? HarnessItem.Skill)?.body
        ?: throw IllegalArgumentException("Requested item is not an enabled skill")

    fun render(name: String, arguments: Map<String, String>): String {
        val template = resolve(name).item as? HarnessItem.Template
            ?: throw IllegalArgumentException("Requested item is not an enabled template")
        return renderHarnessTemplate(template, arguments)
    }

    /** Complete lines form a strict prefix; the final marker is reserved whenever anything is omitted. */
    fun block(): HarnessContentBlock {
        val lines = buildList {
            for (harness in harnesses) {
                add("Харнесс ${harness.name.value}")
                entries.filter { it.harness.id == harness.id }.forEach { entry ->
                    when (val item = entry.item) {
                        is HarnessItem.Instruction -> {
                            add("Инструкция ${entry.qualifiedName}:")
                            addAll(item.text.lines())
                        }

                        is HarnessItem.Skill -> add("- Скилл ${entry.qualifiedName} — ${brief(item.description)}")

                        is HarnessItem.Template -> add("- Шаблон ${entry.qualifiedName} — ${brief(item.description)}")

                        is HarnessItem.Script, is HarnessItem.Workflow -> Unit
                    }
                }
            }
        }
        val complete = prefix(lines, HARNESS_CONTEXT_CHARS)
        val isTruncated = complete.size != lines.size
        val text = if (isTruncated) {
            (prefix(lines, HARNESS_CONTEXT_CHARS - MORE_CONTEXT.length - 1) + MORE_CONTEXT).joinToString("\n")
        } else {
            complete.joinToString("\n")
        }
        return HarnessContentBlock(
            text,
            harnesses.map { HarnessDeliveryMarker(it.id, it.name, it.revision) },
            isTruncated,
        )
    }

    private fun resolve(name: String): ContentEntry {
        val matches = entries.filter { if ('/' in name) it.qualifiedName == name else it.item.name.value == name }
        require(matches.size == 1) { "Content name is missing or ambiguous" }
        return matches.single()
    }

    private fun prefix(lines: List<String>, limit: Int): List<String> {
        val budget = PromptBudget(limit)
        return lines.takeWhile { budget.take(it) != null }
    }
}

/** Rendered instructions and their full accepted-set identity; all content is private. */
internal data class HarnessContentBlock(
    val text: String,
    val markers: List<HarnessDeliveryMarker>,
    val isTruncated: Boolean,
) {
    override fun toString(): String = "HarnessContentBlock(***)"
}

private data class ContentEntry(val harness: Harness, val item: HarnessItem) {
    val qualifiedName: String get() = "${harness.name.value}/${item.name.value}"
    override fun toString(): String = "ContentEntry(***)"
}

internal const val HARNESS_CONTEXT_CHARS = 8192
private const val MORE_CONTEXT = "… Для полного контекста вызови harness_context."
