package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** `.invalid` never resolves, so nothing addressed to a document origin can leave the machine. */
private const val DOCUMENT_HOST_SUFFIX = ".document.heartbeat.invalid"

/** The only address of document [id]: the root of its own origin. */
internal fun desktopDocumentUrl(id: String): String = "https://$id$DOCUMENT_HOST_SUFFIX/"

/**
 * Policy of every document response: scripts and styles only inline, images and media only from `data:` and `blob:`,
 * fonts only from `data:`; no connections (fetch, XHR, WebSocket, EventSource, beacons — WebSocket handshakes never
 * reach CEF request handlers, so this is their only barrier), frames, workers, plugins, manifests, form submissions
 * or base changes. A page's own policy can only narrow it.
 */
internal const val DESKTOP_DOCUMENT_CSP: String = "default-src 'none'; script-src 'unsafe-inline'; " +
    "style-src 'unsafe-inline'; img-src data: blob:; media-src data: blob:; font-src data:; connect-src 'none'; " +
    "frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; manifest-src 'none'; " +
    "form-action 'none'; base-uri 'none'"

/** Headers of a document response besides its type: not cached, not sniffed, no referrer, no device features. */
internal val desktopDocumentHeaders: Map<String, String> = mapOf(
    "Content-Security-Policy" to DESKTOP_DOCUMENT_CSP,
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), display-capture=(), fullscreen=(), " +
        "clipboard-read=(), usb=(), serial=(), hid=(), bluetooth=(), payment=()",
)

/** Function a page exposes for [desktopDocumentDataScript]. */
private const val DATA_FUNCTION = "__heartbeatData"

private val DOCTYPE = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

/**
 * [html] with the page API placed before the page's own markup, after its doctype, which must stay first to keep
 * standards mode; a byte order mark is dropped, the response adds its own. `heartbeat.data` is [data]; with a
 * [queryFunction] of the message router, `heartbeat.call(method, args)` sends `{"method", "args"}` as JSON text and
 * returns a promise of the parsed result, rejected with an `Error` of the failure message.
 */
internal fun desktopDocumentHtml(html: String, data: JsonElement?, queryFunction: String?): String {
    val source = html.removePrefix("\uFEFF")
    val script = "<script>${pageApi(data, queryFunction)}</script>"
    val doctype = DOCTYPE.find(source)?.range?.last?.plus(1) ?: 0
    return source.substring(0, doctype) + script + source.substring(doctype)
}

/** Script that replaces the page's data with [data] and tells the page through a `heartbeat:data` event. */
internal fun desktopDocumentDataScript(data: JsonElement?): String =
    "window.$DATA_FUNCTION && window.$DATA_FUNCTION(${data.scriptLiteral()});"

private fun pageApi(data: JsonElement?, queryFunction: String?): String = """
(() => {
  let data = ${data.scriptLiteral()};
  const query = ${queryFunction?.let { "window[${JsonPrimitive(it).scriptLiteral()}]" } ?: "null"};
  const api = {};
  Object.defineProperty(api, 'data', { get: () => data, enumerable: true });
  if (typeof query === 'function') {
    api.call = (method, args) => new Promise((resolve, reject) => {
      let request;
      try {
        request = JSON.stringify({ method: String(method), args: args === undefined ? null : args });
      } catch (error) {
        reject(error);
        return;
      }
      query({
        request: request,
        persistent: false,
        onSuccess: (response) => resolve(JSON.parse(response)),
        onFailure: (code, message) => reject(new Error(message)),
      });
    });
  }
  Object.defineProperty(window, 'heartbeat', { value: Object.freeze(api), enumerable: true });
  Object.defineProperty(window, '$DATA_FUNCTION', {
    value: (next) => {
      data = next;
      window.dispatchEvent(new CustomEvent('heartbeat:data', { detail: next }));
    },
  });
})();
""".trim()

/**
 * JSON as a script expression inside an inline `<script>`: `<` only occurs inside JSON strings, so escaping it keeps
 * `</script>` and `<!--` in values from ending or changing the element; line and paragraph separators are escaped
 * for engines that predate them in string literals.
 */
private fun JsonElement?.scriptLiteral(): String = (this ?: JsonNull).toString()
    .replace("<", "\\u003c")
    .replace(" ", "\\u2028")
    .replace(" ", "\\u2029")
