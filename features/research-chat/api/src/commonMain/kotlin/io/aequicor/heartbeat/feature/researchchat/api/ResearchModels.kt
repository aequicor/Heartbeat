package io.aequicor.heartbeat.feature.researchchat.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import kotlinx.serialization.Serializable

/** Supported source categories. A document is text or an imported PDF; images retain native image content. */
@Serializable
public enum class ResearchResourceKind { Website, Document, Image }

/** Shared sources are offered to every question; question sources remain local to that question. */
public enum class ResearchResourceScope { Session, Question }

/** Durable source content. [value] is a URL, an inline data URL or document text; it must never be logged. */
@Serializable
public data class ResearchResource(
    val id: String,
    val title: String,
    val kind: ResearchResourceKind,
    val value: String,
    val mediaType: String,
    val text: String = "",
) {
    override fun toString(): String = "ResearchResource(id=$id, kind=$kind)"
}

/** One independent conversation, with its own native session and source choices. */
@Serializable
public data class ResearchQuestion(
    val id: String,
    val title: String = "",
    val ref: SessionRef? = null,
    val items: List<SessionItem> = emptyList(),
    val resourceIds: Set<String> = emptySet(),
    val excludedResourceIds: Set<String> = emptySet(),
    val hasFailed: Boolean = false,
    val pendingSegmentStart: Int? = null,
)

/** A logical research session. Every question uses [target], with no project/workspace attached. */
@Serializable
public data class ResearchSession(
    val id: String,
    val title: String,
    val target: EngineTarget,
    val questions: List<ResearchQuestion>,
    val resources: List<ResearchResource> = emptyList(),
    val sharedResourceIds: Set<String> = emptySet(),
) {
    /** Ordered sources selected for this question, with duplicates removed by catalog identity. */
    public fun selectedResources(question: ResearchQuestion): List<ResearchResource> = resources.filter {
        it.id in sharedResourceIds + question.resourceIds && it.id !in question.excludedResourceIds
    }
}

/** Profile snapshot. Running question ids are transient; saved transcripts and sources survive reopening. */
public data class ResearchWorkspace(
    val sessions: List<ResearchSession> = emptyList(),
    val running: Set<String> = emptySet(),
)
