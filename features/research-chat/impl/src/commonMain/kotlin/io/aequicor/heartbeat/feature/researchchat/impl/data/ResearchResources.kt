package io.aequicor.heartbeat.feature.researchchat.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContentPart
import io.aequicor.heartbeat.feature.aiengine.facade.api.MessageRole
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionItem
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallStatus
import io.aequicor.heartbeat.feature.researchchat.api.ResearchLoadState
import io.aequicor.heartbeat.feature.researchchat.api.ResearchQuestion
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResource
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceScope
import io.aequicor.heartbeat.feature.researchchat.api.ResearchSession
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

private val resourceLog = Log.tag("ResearchResources")

/**
 * First-question sources are always shared; subsequent discoveries stay local unless explicitly
 * shared.
 */
internal fun ResearchSession.attach(
    questionId: String,
    source: ResearchResource,
    scope: ResearchResourceScope,
): ResearchSession {
    val question = questions.first { it.id == questionId }
    val existing = resources.firstOrNull {
        (it.kind == source.kind && it.value == source.value) ||
            (it.sourceUrl != null && it.sourceUrl == (source.sourceUrl ?: source.value))
    }
    val resource = source.merge(existing)
    val isShared = scope == ResearchResourceScope.Session || questions.first().id == questionId ||
        resource.id in sharedResourceIds
    return copy(
        resources = resources.filterNot { it.id == resource.id } + resource,
        sharedResourceIds = if (isShared) sharedResourceIds + resource.id else sharedResourceIds,
        questions = questions.map {
            if (it.id != question.id) {
                it
            } else {
                it.copy(
                    resourceIds = if (isShared) {
                        it.resourceIds - resource.id
                    } else {
                        it.resourceIds + resource.id
                    },
                    excludedResourceIds = it.excludedResourceIds - resource.id,
                )
            }
        },
    )
}

private fun ResearchResource.merge(existing: ResearchResource?): ResearchResource = when {
    existing == null -> this

    existing.loadState == ResearchLoadState.Ready &&
        (loadState != ResearchLoadState.Ready || (text.isBlank() && attachmentId == null)) -> existing

    else -> copy(
        id = existing.id,
        text = text.ifBlank { existing.text },
    )
}

/** Builds each native segment from visible conversation and the current source selection only. */
internal fun ResearchSession.promptParts(question: ResearchQuestion, prompt: String): List<ContentPart> = buildList {
    if (prompt.isNotBlank()) add(ContentPart.Text(prompt))
    val previous = question.items.filterIsInstance<SessionItem.Message>().mapNotNull { message ->
        if (message.role == MessageRole.System) return@mapNotNull null
        val text = message.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
        text.takeIf { it.isNotBlank() }?.let { "${message.role}: $it" }
    }.joinToString("\n\n")
    if (previous.isNotBlank()) {
        add(
            textResource("Previous conversation for this question, supplied as context:\n$previous"),
        )
    }
    val selected = selectedResources(question)
    add(
        textResource(
            "Sources selected for this question: ${selected.size}. Other source attachments are unavailable.",
        ),
    )
    selected.forEach { resource ->
        add(textResource("Selected source: ${resource.title}" + resource.citation()))
        add(resource.contentPart())
    }
}

private fun ResearchResource.contentPart(): ContentPart = when (kind) {
    ResearchResourceKind.Image -> ContentPart.Image(ResourceRef(value, mediaType))

    ResearchResourceKind.Document -> if (attachmentId != null || mediaType == "application/pdf") {
        ContentPart.Resource(ResourceRef(value, mediaType))
    } else {
        textResource(text.ifBlank { value })
    }

    ResearchResourceKind.Website -> textResource(text)
}

private fun ResearchResource.citation(): String =
    (sourceUrl ?: value.takeIf { kind == ResearchResourceKind.Website })?.let { "\nURL: $it" }.orEmpty()

private fun textResource(value: String) = ContentPart.Resource(
    ResourceRef(
        "data:text/plain;base64,${Base64.encode(value.encodeToByteArray())}",
        "text/plain",
    ),
)

/**
 * Structured search results and, with recovery enabled, the requested URL of failed fetches.
 * Prose URLs in answers and errors are never automatically imported.
 */
internal fun discoveredResources(items: List<SessionItem>, recoverFailures: Boolean = false): List<ResearchResource> {
    val calls = items.filterIsInstance<SessionItem.ToolCall>()
        .filter { it.status == ToolCallStatus.Succeeded && it.name in setOf("web_search", "web_fetch") }
        .associateBy { it.call }
    val successful = items.asSequence().filterIsInstance<SessionItem.ToolResult>().filter { it.failure == null }
        .flatMap { result ->
            val call = calls[result.call] ?: return@flatMap emptyList()
            val text = result.parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
            try {
                val parsed = Json.parseToJsonElement(text)
                val objects = if (call.name == "web_search") {
                    (parsed as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                } else {
                    listOfNotNull(parsed as? JsonObject)
                }
                objects.mapNotNull { it.toResource() }
            } catch (error: IllegalArgumentException) {
                // JSON parser messages can contain source content; log a safe wrapper instead.
                resourceLog.w(IllegalArgumentException(error::class.simpleName.orEmpty())) {
                    "Search result could not be imported"
                }
                emptyList()
            }
        }.toList()
    val failed = if (recoverFailures) {
        items.asSequence().filterIsInstance<SessionItem.ToolCall>()
            .filter { it.name == "web_fetch" && it.status == ToolCallStatus.Failed }
            .mapNotNull { it.failedResource() }
            .toList()
    } else {
        emptyList()
    }
    return (successful + failed).groupBy {
        it.value
    }.values.map { versions -> versions.lastOrNull { it.text.isNotBlank() } ?: versions.last() }
}

private fun SessionItem.ToolCall.failedResource(): ResearchResource? = try {
    val url = (Json.parseToJsonElement(arguments) as? JsonObject)?.string("url").orEmpty().trim()
    if (isWebUrl(url)) {
        ResearchResource(
            Uuid.random().toString(),
            url,
            ResearchResourceKind.Website,
            url,
            "text/html",
            loadState = ResearchLoadState.Failed,
            loadError = "fetch_failed",
        )
    } else {
        null
    }
} catch (error: IllegalArgumentException) {
    resourceLog.w(IllegalArgumentException(error::class.simpleName.orEmpty())) {
        "Failed fetch arguments could not be imported"
    }
    null
}

private fun JsonObject.toResource(): ResearchResource? {
    val url = string("url").trim()
    if (!isWebUrl(url)) return null
    return ResearchResource(
        Uuid.random().toString(),
        string("title").ifBlank { url },
        ResearchResourceKind.Website,
        url,
        "text/html",
        string("content"),
    )
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

internal fun isWebUrl(value: String): Boolean = (value.startsWith("https://") || value.startsWith("http://")) &&
    value.substringAfter("://").isNotBlank() && value.none { it.isWhitespace() }

/**
 * Search snippets identify candidates; selected websites are read before another question uses
 * them.
 */
internal suspend fun loadSelectedSources(
    session: ResearchSession,
    question: ResearchQuestion,
    search: SearchEngine,
    recoverFailures: Boolean = false,
): ResearchSession {
    val missing = session.selectedResources(question).filter {
        it.kind == ResearchResourceKind.Website && it.text.isBlank()
    }
    val loaded = missing.map { source ->
        try {
            val page = search.fetch(source.value)
            require(page.text.isNotBlank()) { "Selected website returned no content" }
            source.copy(
                text = page.text,
                loadState = ResearchLoadState.Ready,
                loadError = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!recoverFailures) throw error
            resourceLog.w(IllegalStateException(error::class.simpleName)) {
                "Research source requires retry"
            }
            source.copy(loadState = ResearchLoadState.Failed, loadError = "fetch_failed")
        }
    }.associateBy { it.id }
    return session.copy(resources = session.resources.map { loaded[it.id] ?: it })
}

/**
 * Attachments live in the source catalog; answer prose and explicitly exposed reasoning remain in
 * history.
 */
internal fun SessionItem.withoutAttachments(): SessionItem = if (this is SessionItem.Message) {
    copy(parts = parts.filter { it is ContentPart.Text || it is ContentPart.Reasoning })
} else {
    this
}
