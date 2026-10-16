package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFormat
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigSchema
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.document
import io.aequicor.heartbeat.feature.aiengine.facade.api.withDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Page id of the editor of the file [id]. */
internal fun filePage(id: String): String = "$FILE_PAGE$id"

/** The file id of a file editor page, or null for a configuration page. */
internal fun fileOf(page: String): String? = page.removePrefix(FILE_PAGE).takeIf { page.startsWith(FILE_PAGE) }

/** The files of [scope] with the text each shows for [values] and the [typed] texts. */
internal fun EngineConfigSchema.files(
    scope: ConfigScope,
    values: EngineConfigValues,
    typed: Map<String, String>,
): List<ConfigFileUi> = documents.filter { scope in it.scopes }.map { spec ->
    val raw = typed[spec.id.value]
    ConfigFileUi(
        id = spec.id.value,
        title = spec.title.toUi(),
        fileName = spec.fileName,
        text = raw ?: values.document(spec.id).render(),
        language = if (spec.format == ConfigFormat.Json) "json" else null,
        isInvalid = raw != null && spec.format == ConfigFormat.Json && parseJsonObject(raw).isFailure,
    )
}

/**
 * [draft] (or a new one) with the file [id] replaced by [text]: a JSON file that parses replaces its document, one
 * that does not is kept as typed and blocks saving; an empty file removes the document.
 */
internal fun EngineConfigState.editFile(draft: ConfigDraft?, id: String, text: String): ConfigDraft? =
    if (draft?.isSubmitted == true || schema.documents.none { it.id.value == id }) {
        draft
    } else {
        profileSource.editFile(draft ?: ConfigDraft(revision, profile), id, text)
    }

/** [draft] with the file [id] of this source replaced by [text]; see [EngineConfigState.editFile]. */
internal fun FormSource.editFile(draft: ConfigDraft, id: String, text: String): ConfigDraft {
    val spec = schema.documents.firstOrNull { it.id.value == id && scope in it.scopes } ?: return draft
    val current = draft
    val document = when {
        text.isBlank() -> null
        spec.format == ConfigFormat.Text -> ConfigDocument.Text(text)
        else -> parseJsonObject(text).getOrNull()?.let(ConfigDocument::Json)
    }
    // A JSON file that does not parse leaves the document as it was until it does.
    val isUnparsed = document == null && text.isNotBlank()
    val values = if (isUnparsed) current.values else current.values.withDocument(spec.id, document)
    return current.copy(values = values, files = current.files + (id to text))
}

/** [draft] without the typed texts that parse, so their editors show the documents again. */
internal fun EngineConfigSchema.settleFiles(draft: ConfigDraft): ConfigDraft {
    val invalid = draft.files.filter { (id, text) ->
        document(
            ConfigDocumentId(id),
        )?.format == ConfigFormat.Json && text.isNotBlank() && parseJsonObject(text).isFailure
    }
    return draft.copy(files = invalid)
}

/** The JSON object [text] holds; a failure when it does not parse or holds something else. */
internal fun parseJsonObject(text: String): Result<JsonObject> = try {
    Result.success(Json.parseToJsonElement(text).jsonObject)
} catch (e: IllegalArgumentException) {
    Result.failure(e)
}

private fun ConfigDocument?.render(): String = when (this) {
    is ConfigDocument.Json -> PrettyJson.encodeToString(JsonObject.serializer(), value)
    is ConfigDocument.Text -> value
    null -> ""
}

private const val FILE_PAGE = "file:"

private val PrettyJson = Json { prettyPrint = true }
