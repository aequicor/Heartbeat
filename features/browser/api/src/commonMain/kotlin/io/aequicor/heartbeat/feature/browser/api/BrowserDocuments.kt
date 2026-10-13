package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Isolated local documents of the active profile: HTML a feature shows inside its own screen through
 * [BrowserDocumentRoute], without network access and apart from the user's browser tabs.
 *
 * Desktop only: Android and iOS draw the route as a "not supported" note. Every document gets its own origin
 * (`https://<id>.document.heartbeat.invalid/`) in an in-memory context with no cookies or storage shared with other
 * documents or the profile's browser; a content security policy and the request handler block every request except
 * the document itself and `data:` images and fonts, as well as navigation, pop-ups, downloads and dialogs.
 */
public interface BrowserDocuments {
    /**
     * Registers [document] until its handle is closed or the profile closes; the returned handle's route draws it.
     * Null when its HTML exceeds [BrowserDocumentLimits.HTML_BYTES], its data [BrowserDocumentLimits.DATA_BYTES], or
     * [BrowserDocumentLimits.DOCUMENTS] documents are already open.
     */
    public fun open(document: BrowserDocument): BrowserDocumentHandle?
}

/**
 * HTML shown by [BrowserDocuments]. [title] is the accessible name of the embedded page. The page reads [data] as
 * `heartbeat.data` and receives later values from [BrowserDocumentHandle.update] as a `heartbeat:data` event on
 * `window` whose `detail` is the new value. With a [bridge], `heartbeat.call(method, args)` returns a promise of the
 * bridge's result and rejects with an `Error` of its message; without one `heartbeat.call` is absent.
 */
public data class BrowserDocument(
    val html: String,
    val title: String,
    val data: JsonElement? = null,
    val bridge: BrowserDocumentBridge? = null,
) {
    override fun toString(): String = "BrowserDocument(bridge=${bridge != null})"
}

/**
 * Calls of a page into its opener. Runs off the main thread with page input already bounded by
 * [BrowserDocumentLimits]; cancelled when the page reloads, its view closes or the document closes.
 */
public fun interface BrowserDocumentBridge {
    /** Handles `heartbeat.call([method], [arguments])` of the page. */
    public suspend fun call(method: String, arguments: JsonElement): BrowserBridgeResult
}

/** Outcome of [BrowserDocumentBridge.call], delivered to the page's promise. */
public sealed interface BrowserBridgeResult {
    /** Resolves the promise with [value]; a value over [BrowserDocumentLimits.RESULT_BYTES] rejects it instead. */
    public data class Value(val value: JsonElement) : BrowserBridgeResult {
        override fun toString(): String = "Value(value=***)"
    }

    /** Rejects the promise with [message], cut to [BrowserDocumentLimits.ERROR_CHARS]. */
    public data class Failure(val message: String) : BrowserBridgeResult {
        override fun toString(): String = "Failure(message=***)"
    }
}

/** An open document. Closing it closes its views and cancels the page's pending calls; it cannot reopen. */
public interface BrowserDocumentHandle {
    /** Draws the document; a route of a closed document draws a "closed" note. */
    public val route: BrowserDocumentRoute

    /** Replaces the page's data; ignored once closed or when [data] exceeds [BrowserDocumentLimits.DATA_BYTES]. */
    public fun update(data: JsonElement?)

    /** Closes the document; repeated calls do nothing. */
    public fun close()
}

/** Embeds the document [id] of [BrowserDocuments] in the opener's screen; it fills the size it is given. */
@Serializable
@SerialName("browser_document")
public data class BrowserDocumentRoute(val id: String) : Route

/** Bounds of documents and of what their pages send and receive. */
public object BrowserDocumentLimits {
    /** UTF-8 bytes of a document's HTML. */
    public const val HTML_BYTES: Int = 256 * 1024

    /** UTF-8 bytes of a document's data as JSON. */
    public const val DATA_BYTES: Int = 512 * 1024

    /** Open documents per profile. */
    public const val DOCUMENTS: Int = 32

    /** Characters of a called method's name. */
    public const val METHOD_CHARS: Int = 64

    /** UTF-8 bytes of one call of a page: its method and arguments as JSON. */
    public const val CALL_BYTES: Int = 64 * 1024

    /** Nesting of a call's arguments; deeper JSON is rejected before it is parsed. */
    public const val JSON_DEPTH: Int = 64

    /** UTF-8 bytes of a call's result as JSON. */
    public const val RESULT_BYTES: Int = 512 * 1024

    /** Characters of a failure message delivered to the page. */
    public const val ERROR_CHARS: Int = 500

    /** Pending calls of one page; further calls are rejected at once. */
    public const val CALLS: Int = 4
}
