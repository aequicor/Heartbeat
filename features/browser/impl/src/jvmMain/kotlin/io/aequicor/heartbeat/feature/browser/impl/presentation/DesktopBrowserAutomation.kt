package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * Private CDP transport: no debugging port and no arbitrary JavaScript tool. Coordinates use
 * viewport CSS pixels.
 *
 * A snapshot (read or screenshot) stays valid while the document, its URL, the viewport size and the scroll
 * position are unchanged; ordinary DOM mutations (timers, carousels) do not invalidate it. Right before input an
 * element reference is checked again: it must still mean what the read reported (tag, role, type, link target and
 * label) and be hit at its centre, without a link or control inserted inside it. A read is one synchronous script,
 * so the content and the geometry it reports describe the same moment; a screenshot is retried a bounded number of
 * times while the viewport moves.
 */
internal class DesktopBrowserAutomation(
    private val transport: suspend (String, JsonObject) -> String,
    private val loadResource: suspend (String) -> JsonObject = { error("Browser resource loading is unavailable") },
) {
    private val log = Log.tag("BrowserAutomation")
    private var executionContext: JsonElement? = null
    private val worldName = "heartbeat-" + Uuid.random()
    private var snapshot: String? = null
    private var hasElements = false
    private var geometry: JsonElement? = null

    fun invalidate() {
        snapshot = null
        geometry = null
        hasElements = false
        executionContext = null
    }

    suspend fun captureResource(request: BrowserRequest): BrowserResult {
        val address = requireNotNull(request.arguments["url"]?.jsonPrimitive?.content)
        val resource = loadResource(address)
        if (resource["kind"]?.jsonPrimitive?.content == "document") {
            return BrowserResult(request.id, resource)
        }
        val url = resource.getValue("url").jsonPrimitive.content
        val navigation = call("Page.navigate", buildJsonObject { put("url", url) })
        check(navigation["errorText"] == null) { "Source navigation failed" }
        val loader =
            requireNotNull(navigation["loaderId"]) { "Source navigation was not committed" }
        invalidate()
        withFailureTimeout(NAVIGATION_TIMEOUT, log, "Source navigation") {
            while (frame()["loaderId"] != loader) delay(FRAME_POLL_INTERVAL)
        }
        evaluate(
            "new Promise(resolve => document.readyState === 'complete' ? resolve(true) : " +
                "window.addEventListener('load', () => resolve(true), {once:true}))",
        )
        // The host may resize the viewport meanwhile (the recovery UI changes its layout); only the document matters.
        val content = read()
        check(frame()["loaderId"] == loader) { "Source navigation replaced" }
        check(content["url"]?.jsonPrimitive?.content == url) {
            "Source redirected; reopen it first"
        }
        return BrowserResult(request.id, JsonObject(content + ("tabId" to JsonPrimitive(request.tabId))))
    }

    private suspend fun frame(): JsonObject = call("Page.getFrameTree", JsonObject(emptyMap()))
        .getValue("frameTree")
        .jsonObject
        .getValue("frame")
        .jsonObject

    /**
     * A synchronous observation for independent safety review. It does not replace the agent's last read or its
     * element references. Document, node identity, focus, form and target geometry are compared again before input.
     */
    suspend fun safetySnapshot(operation: BrowserOperation?, arguments: JsonObject): JsonObject {
        if (operation in setOf(BrowserOperation.Click, BrowserOperation.Scroll, BrowserOperation.Drag)) {
            validate(arguments)
        }
        return evaluate(browserSafetyScript(GEOMETRY_SCRIPT, operation, arguments)).jsonObject
    }

    suspend fun perform(request: BrowserRequest): BrowserResult {
        if (request.operation == BrowserOperation.Screenshot) return screenshot(request)
        val args = request.arguments
        val data =
            when (request.operation) {
                BrowserOperation.Read -> read()

                BrowserOperation.Click -> click(args)

                BrowserOperation.Type -> type(args)

                BrowserOperation.Key -> key(args)

                BrowserOperation.Scroll -> scroll(args)

                BrowserOperation.Drag -> drag(args)

                BrowserOperation.RequestControl,
                BrowserOperation.ApproveControl,
                BrowserOperation.RejectControl,
                BrowserOperation.ListTabs,
                BrowserOperation.NewTab,
                BrowserOperation.CloseTab,
                BrowserOperation.Navigate,
                BrowserOperation.Search,
                BrowserOperation.Screenshot,
                BrowserOperation.TakeControl,
                BrowserOperation.ReturnControl,
                BrowserOperation.CaptureResource,
                BrowserOperation.ClearData,
                -> error("Unsupported native operation")
            }
        return BrowserResult(
            request.id,
            JsonObject(data + ("tabId" to JsonPrimitive(request.tabId))),
        )
    }

    /**
     * One synchronous script returns the content together with the geometry it was read at: no page task runs in
     * between, so a mutating page cannot make the read inconsistent. Element references are hit-tested on use.
     */
    private suspend fun read(): JsonObject {
        val content = evaluate(READ_SCRIPT).jsonObject
        hasElements = true
        snapshot = Uuid.random().toString()
        geometry = content.getValue("view")
        return JsonObject(content - "view" + ("snapshotId" to JsonPrimitive(snapshot)))
    }

    private suspend fun screenshot(request: BrowserRequest): BrowserResult {
        hasElements = false
        snapshot = null
        geometry = null
        repeat(MAX_CAPTURE_ATTEMPTS) {
            val before = evaluate(GEOMETRY_SCRIPT)
            val response =
                call(
                    "Page.captureScreenshot",
                    buildJsonObject {
                        put("format", "png")
                        put("captureBeyondViewport", false)
                    },
                )
            val image = response["data"]?.jsonPrimitive?.content ?: error("Screenshot unavailable")
            require(image.length <= MAX_IMAGE_BASE64) { "Screenshot exceeds output limit" }
            // Only another document, URL, size or scroll position moves what the image coordinates point at.
            if (evaluate(GEOMETRY_SCRIPT) == before) return screenshotResult(request, image, before)
            log.d { "Browser viewport moved while capturing; retrying" }
        }
        error("Page kept scrolling or navigating while capturing; capture it again")
    }

    private fun screenshotResult(request: BrowserRequest, image: String, view: JsonElement): BrowserResult {
        geometry = view
        snapshot = Uuid.random().toString()
        val header = Base64.getDecoder().decode(image.take(PNG_HEADER_BASE64_SIZE))
        val imageWidth = header.pngDimension(PNG_WIDTH_OFFSET)
        val imageHeight = header.pngDimension(PNG_HEIGHT_OFFSET)
        return BrowserResult(
            request.id,
            buildJsonObject {
                put("snapshotId", snapshot)
                put("viewport", geometry ?: JsonNull)
                put("coordinateSpace", "cssPixels")
                put("imageWidth", imageWidth)
                put("imageHeight", imageHeight)
            },
            imageBase64 = image,
        )
    }

    private suspend fun click(args: JsonObject): JsonObject {
        validate(args)
        val ref = args["element"]?.jsonPrimitive?.intOrNull
        val point =
            if (ref != null) {
                require(hasElements) { "Read the page again to get element references" }
                require(ref in 0 until MAX_ELEMENTS) { "Invalid element reference" }
                evaluate(
                    """
                    (() => {
                        const e = window.__heartbeatElements?.[$ref];
                        if (!e || !e.isConnected) throw Error('Stale element');
                        if ($FINGERPRINT_SCRIPT(e) !== window.__heartbeatFingerprints?.[$ref]) return {isChanged:true};
                        const r=e.getBoundingClientRect();
                        const x=r.x+r.width/2,y=r.y+r.height/2;
                        const hit=document.elementFromPoint(x,y);
                        return {x,y,isChanged:false,
                            isVisible:e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}),
                            isTarget:!!hit&&e.contains(hit)&&hit.closest('$INTERACTIVE_SELECTOR')===e};
                    })()
                    """.trimIndent(),
                )
                    .jsonObject
                    .also { target ->
                        require(target["isChanged"]?.jsonPrimitive?.booleanOrNull == false) {
                            "Element changed; read the page again"
                        }
                        require(target["isVisible"]?.jsonPrimitive?.booleanOrNull == true) {
                            "Element is hidden; read the page again"
                        }
                        require(target["isTarget"]?.jsonPrimitive?.booleanOrNull == true) {
                            "Element is covered or outside the viewport; read or capture the page again"
                        }
                    }
            } else {
                args
            }
        press(point, point)
        invalidate()
        return buildJsonObject { put("clicked", true) }
    }

    private suspend fun type(args: JsonObject): JsonObject {
        val text = args["text"]?.jsonPrimitive?.content ?: error("Text required")
        require(text.length <= MAX_TEXT_LENGTH) { "Text exceeds input limit" }
        call("Input.insertText", buildJsonObject { put("text", text) })
        invalidate()
        return buildJsonObject { put("typed", true) }
    }

    private suspend fun key(args: JsonObject): JsonObject {
        val key = args["key"]?.jsonPrimitive?.content ?: error("Key required")
        val code = KEYS[key] ?: error("Unsupported key")
        var isReleased = false
        try {
            call(
                "Input.dispatchKeyEvent",
                buildJsonObject {
                    put("type", "keyDown")
                    put("key", key)
                    put("windowsVirtualKeyCode", code)
                },
            )
        } finally {
            // The keyDown request may have reached the page even when it failed or was cancelled.
            isReleased = withContext(NonCancellable) {
                release("key") {
                    call(
                        "Input.dispatchKeyEvent",
                        buildJsonObject {
                            put("type", "keyUp")
                            put("key", key)
                            put("windowsVirtualKeyCode", code)
                        },
                    )
                }
            }
        }
        check(isReleased) { "Key release failed; read the page again" }
        invalidate()
        return buildJsonObject { put("pressed", true) }
    }

    private suspend fun scroll(args: JsonObject): JsonObject {
        validate(args)
        val dy = args["deltaY"]?.jsonPrimitive?.doubleOrNull ?: error("deltaY required")
        require(dy.isFinite() && dy in -MAX_SCROLL..MAX_SCROLL) { "Invalid scroll distance" }
        mouse("mouseWheel", args, dy)
        invalidate()
        return buildJsonObject { put("scrolled", true) }
    }

    private suspend fun drag(args: JsonObject): JsonObject {
        validate(args)
        val destination = buildJsonObject {
            put("x", args["toX"] ?: JsonNull)
            put("y", args["toY"] ?: JsonNull)
        }
        validatePoint(destination)
        press(args, destination) { mouse("mouseMoved", destination) }
        invalidate()
        return buildJsonObject { put("dragged", true) }
    }

    private suspend fun validate(args: JsonObject) {
        require(snapshot != null && args["snapshotId"]?.jsonPrimitive?.content == snapshot) {
            "Read or capture the page again"
        }
        require(evaluate(GEOMETRY_SCRIPT) == geometry) { "Page changed; read or capture it again" }
    }

    /**
     * Presses at [point], runs [between] and releases at [releasePoint]. The release follows only an issued press
     * request (a cancelled request may still have reached the page), never a press refused by validation.
     */
    private suspend fun press(point: JsonObject, releasePoint: JsonObject, between: suspend () -> Unit = {}) {
        validatePoint(point)
        var isReleased = false
        try {
            mouse("mousePressed", point)
            between()
        } finally {
            isReleased = withContext(NonCancellable) {
                release("mouse button") { mouse("mouseReleased", releasePoint) }
            }
        }
        check(isReleased) { "Mouse button release failed; read the page again" }
    }

    /**
     * Bounded input cleanup; callers run it under NonCancellable so it also follows a cancelled operation. A timeout
     * or failure is logged and returned as false instead of replacing the operation's own outcome from a finally.
     */
    private suspend fun release(input: String, action: suspend () -> Unit): Boolean = try {
        val isReleased = withTimeoutOrNull(INPUT_RELEASE_TIMEOUT) { action() } != null
        if (!isReleased) {
            log.w(IllegalStateException("Browser $input release timed out")) { "Browser input release timed out" }
        }
        isReleased
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error) { "Browser $input release failed" }
        false
    }

    private suspend fun mouse(type: String, args: JsonObject, deltaY: Double? = null) {
        val x = args["x"]?.jsonPrimitive?.doubleOrNull ?: error("x required")
        val y = args["y"]?.jsonPrimitive?.doubleOrNull ?: error("y required")
        // Presses are validated by press() before they are issued; a release must always follow its press.
        if (type == "mouseWheel" || type == "mouseMoved") validatePoint(args)
        call(
            "Input.dispatchMouseEvent",
            buildJsonObject {
                put("type", type)
                put("x", x)
                put("y", y)
                if (deltaY == null) {
                    put("button", "left")
                    put("buttons", if (type == "mouseReleased") 0 else 1)
                    put("clickCount", 1)
                } else {
                    put("deltaX", 0)
                    put("deltaY", deltaY)
                }
            },
        )
    }

    private suspend fun validatePoint(args: JsonObject) {
        val x = args["x"]?.jsonPrimitive?.doubleOrNull ?: error("x required")
        val y = args["y"]?.jsonPrimitive?.doubleOrNull ?: error("y required")
        val view = evaluate(GEOMETRY_SCRIPT).jsonObject
        val width = view.getValue("width").jsonPrimitive.double
        val height = view.getValue("height").jsonPrimitive.double
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height) {
            "Point outside viewport"
        }
    }

    private suspend fun evaluate(script: String): JsonElement {
        var isEvaluated = false
        try {
            val context = executionContext ?: call(
                "Page.createIsolatedWorld",
                buildJsonObject {
                    put("frameId", frame().getValue("id"))
                    put("worldName", worldName)
                },
            )
                .getValue("executionContextId")
                .also { executionContext = it }
            val response =
                call(
                    "Runtime.evaluate",
                    buildJsonObject {
                        put("contextId", context)
                        put("expression", script)
                        put("returnByValue", true)
                        put("awaitPromise", true)
                    },
                )
            isEvaluated = true
            check(response["exceptionDetails"] == null) { "Page changed or cannot be read" }
            return response["result"]?.jsonObject?.get("value") ?: error("Page returned no content")
        } finally {
            // A failed CDP call usually means the isolated world went away with its document: create a new one.
            if (!isEvaluated) executionContext = null
        }
    }

    private suspend fun call(method: String, args: JsonObject): JsonObject =
        Json.parseToJsonElement(transport(method, args)).jsonObject

    private companion object {
        const val PNG_HEADER_BASE64_SIZE = 32
        const val PNG_WIDTH_OFFSET = 16
        const val PNG_HEIGHT_OFFSET = 20
        const val MAX_IMAGE_BASE64 = 12_000_000
        const val MAX_TEXT_LENGTH = 20_000
        const val INPUT_RELEASE_TIMEOUT = 2_000L
        const val MAX_ELEMENTS = 200
        const val MAX_SCROLL = 10_000.0
        const val NAVIGATION_TIMEOUT = 30_000L
        const val FRAME_POLL_INTERVAL = 50L
        const val MAX_CAPTURE_ATTEMPTS = 3
        val KEYS =
            mapOf(
                "Enter" to 13,
                "Tab" to 9,
                "Escape" to 27,
                "Backspace" to 8,
                "Delete" to 46,
                "ArrowLeft" to 37,
                "ArrowUp" to 38,
                "ArrowRight" to 39,
                "ArrowDown" to 40,
                "Home" to 36,
                "End" to 35,
            )

        /** Document identity, URL, viewport size and scroll position; DOM mutations are deliberately excluded. */
        const val GEOMETRY_SCRIPT =
            """(() => {
            if (!window.__heartbeatDocument) {
                window.__heartbeatDocument=crypto.randomUUID?.() || `${'$'}{Date.now()}-${'$'}{Math.random()}`;
            }
            return {document:window.__heartbeatDocument,url:location.href,width:innerWidth,height:innerHeight,x:scrollX,y:scrollY};
        })()"""

        /** The label an element reference is shown with. */
        const val LABEL_SCRIPT =
            "(e=>(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,300))"

        /**
         * What a reference means to the agent and to the user approving a click: tag, role, type, link target and
         * label. The resolved link target also detects changes to the document base URL between read and click.
         */
        const val FINGERPRINT_SCRIPT =
            "(e=>JSON.stringify([e.tagName,e.getAttribute('role'),e.getAttribute('type')," +
                "e.getAttribute('href'),e.href||null,e.getAttribute('aria-label'),$LABEL_SCRIPT(e)]))"

        /**
         * Elements that act on a click by themselves. A click lands on its referenced element only when the nearest
         * such ancestor of the hit-tested node is that element, not a link or control inserted inside it.
         */
        const val INTERACTIVE_SELECTOR =
            "a[href],area[href],button,input,textarea,select,summary,[role=button],[role=link],[role=checkbox]," +
                "[role=radio],[role=switch],[role=tab],[role=menuitem],[role=option],[onclick]," +
                "[contenteditable]:not([contenteditable=false i])"

        /** Synchronous: the geometry ("view") and the content describe the same moment. */
        const val READ_SCRIPT =
            """(() => {
            const view=$GEOMETRY_SCRIPT;
            const elements=Array.from(document.querySelectorAll('a[href],button,input,textarea,select,[role=button]'))
                .filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})}).slice(0,200);
            window.__heartbeatElements=elements;
            window.__heartbeatFingerprints=elements.map($FINGERPRINT_SCRIPT);
            return {view,url:location.href,title:document.title,
                requiresUserAction:!!document.querySelector('input[type=password],iframe[src*="captcha"]'),content:(document.body?.innerText||'').slice(0,100000),
                elements:elements.map((e,id)=>({id,tag:e.tagName,label:$LABEL_SCRIPT(e),href:e.href||null}))};
        })()"""
    }
}

/**
 * A timeout is an operation failure, not a cancellation: [kotlinx.coroutines.withTimeout] would surface it as a
 * CancellationException that callers treat as their own cancellation, without a warning. Logs and throws
 * IllegalStateException("<operation> timed out").
 */
internal suspend fun <T : Any> withFailureTimeout(
    timeout: Long,
    log: Log,
    operation: String,
    block: suspend CoroutineScope.() -> T,
): T = withTimeoutOrNull(timeout, block) ?: run {
    val failure = IllegalStateException("$operation timed out")
    log.w(failure) { "$operation timed out after ${timeout}ms" }
    throw failure
}

private fun ByteArray.pngDimension(offset: Int): Int = (offset until offset + Int.SIZE_BYTES).fold(0) { value, index ->
    (value shl Byte.SIZE_BITS) or this[index].toUByte().toInt()
}
