package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.link.panelJson
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A call carrying the next contiguous part of the shared studio sequence. */
internal data class StudioBatchCall(val method: String, val params: JsonElement)

/** Page capture is a single call between ordinary batches, never reordered ahead of edits or behind submit. */
internal fun List<PendingIntent>.nextStudioBatch(): List<PendingIntent> {
    if (isEmpty()) return this
    return if (first().page() != null) take(1) else takeWhile { it.page() == null }
}

/** Encodes one page capture or an ordinary batch without changing its sequence/generation identity. */
internal fun studioBatchCall(view: String, generation: String, batch: List<PendingIntent>): StudioBatchCall {
    val first = batch.first()
    val page = first.page()
    return if (page != null) {
        val paneId = (first.intent as AiStudioScreenIntent.ImportAttachments).paneId
        val wire = WirePage(url = page.url, title = page.title, text = page.text, selection = page.selection)
        val params = ViewPage(view, generation, first.seq, paneId, wire)
        StudioBatchCall("studio.attachPage", panelJson.encodeToJsonElement(ViewPage.serializer(), params))
    } else {
        val intents = batch.map {
            ViewIntent(it.seq, StudioViewJson.encodeToJsonElement(AiStudioScreenIntent.serializer(), it.intent))
        }
        val params = ViewIntents(view, generation, intents)
        StudioBatchCall("studio.intent", panelJson.encodeToJsonElement(ViewIntents.serializer(), params))
    }
}

private fun PendingIntent.page(): NativeAttachmentUi.Page? =
    (intent as? AiStudioScreenIntent.ImportAttachments)?.inputs?.singleOrNull() as? NativeAttachmentUi.Page

/** A numbered page capture. */
@Serializable
private data class ViewPage(
    val view: String,
    val generation: String,
    val seq: Long,
    val paneId: Int,
    val page: WirePage,
)

/** Ordinary numbered screen inputs. */
@Serializable
private data class ViewIntents(val view: String, val generation: String, val intents: List<ViewIntent>)

/** One input in the common sequence. */
@Serializable
private data class ViewIntent(val seq: Long, val intent: JsonElement)
