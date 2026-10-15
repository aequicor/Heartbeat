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
 * Host colors and typography are exposed as `--hb-*` CSS variables and `heartbeat.theme`; `heartbeat:theme`
 * announces changes without reloading the document or replacing its data. These presentation facilities confer no
 * method-call authority. [sizing] opts into bounded intrinsic height; fixed-size callers retain their viewport.
 */
public data class BrowserDocument(
    val html: String,
    val title: String,
    val data: JsonElement? = null,
    val bridge: BrowserDocumentBridge? = null,
    val sizing: BrowserDocumentSizing = BrowserDocumentSizing.Fixed,
) {
    override fun toString(): String = "BrowserDocument(bridge=${bridge != null})"
}

/** How a document route uses the size its parent allows. No setting can exceed the host's layout constraints. */
public enum class BrowserDocumentSizing {
    /** Fill the caller's viewport, as before. */
    Fixed,

    /** Follow intrinsic body height within host minimum/maximum tokens; use a regular viewport until measured. */
    Content,
}

/**
 * Calls of a page into its opener. [call] runs off the main thread with page input already bounded by
 * [BrowserDocumentLimits]; cancelled when the page reloads or the document closes. Hiding a retained view does not
 * reload the page or cancel its work.
 */
public fun interface BrowserDocumentBridge {
    /**
     * Captures authority when the native transport accepts a request, before parsing or dispatcher queues.
     * Called synchronously on the transport thread: implementations must not block, perform IO or call owner
     * methods. A retained opener returns a callback bound to its current context; revocation must reject that
     * callback's later calls rather than grant a replacement context. Ordinary bridges keep this callback.
     */
    public fun capture(): BrowserDocumentBridge = this

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

/**
 * An open document. Its opener closes it when its owning feature no longer needs it: an open document counts toward
 * [BrowserDocumentLimits.DOCUMENTS] until then. Desktop retains one native page across hiding and moving its view;
 * at most one host displays that page at a time. Closing closes the page and cancels its pending calls; it cannot
 * reopen. A view that cannot show the page explains it itself.
 */
public interface BrowserDocumentHandle {
    /** Draws the document; a route of a closed document draws a "closed" note. */
    public val route: BrowserDocumentRoute

    /** Replaces the page's data; ignored once closed or when [data] exceeds [BrowserDocumentLimits.DATA_BYTES]. */
    public fun update(data: JsonElement?)

    /** Closes the document; repeated calls do nothing. */
    public fun close()
}

/**
 * Embeds document [id] in the opener's screen. Fixed documents fill the given size; content-sized documents follow
 * intrinsic height inside host bounds, falling back to the regular viewport until measurement is available.
 * [presentation] overrides only the host layout, never the registration, page, data or bridge. Hosts may move the
 * same registration between routes without reloading it; the most recently attached host owns its native view.
 */
@Serializable
@SerialName("browser_document")
public data class BrowserDocumentRoute(
    val id: String,
    val presentation: BrowserDocumentPresentation = BrowserDocumentPresentation.Document,
) : Route

/** Layout of a registered document in one host; changing it preserves the page's identity and DOM. */
@Serializable
public enum class BrowserDocumentPresentation {
    /** Honor the registration's [BrowserDocument.sizing]. */
    Document,

    /** Fill the bounded host viewport, including for an intrinsically sized registration. */
    Fill,

    /**
     * Follow intrinsic content height, limited only by the host's incoming minimum and maximum constraints.
     * Tight constraints still fill a host such as a start screen; loose constraints let a modal shrink or grow.
     * Works with either registration sizing mode and uses a regular viewport until its first measurement.
     */
    Content,
}

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
