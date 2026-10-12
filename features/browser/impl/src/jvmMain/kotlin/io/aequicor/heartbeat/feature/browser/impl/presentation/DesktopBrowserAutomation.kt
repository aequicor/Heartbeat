package io.aequicor.heartbeat.feature.browser.impl.presentation

import io.aequicor.heartbeat.feature.browser.api.BrowserOperation
import io.aequicor.heartbeat.feature.browser.api.BrowserRequest
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 */
internal class DesktopBrowserAutomation(
    private val transport: suspend (String, JsonObject) -> String,
    private val loadResource: suspend (String) -> JsonObject = { error("Browser resource loading is unavailable") },
) {
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
        withTimeout(NAVIGATION_TIMEOUT) {
            while (frame()["loaderId"] != loader) delay(FRAME_POLL_INTERVAL)
        }
        evaluate(
            "new Promise(resolve => document.readyState === 'complete' ? resolve(true) : " +
                "window.addEventListener('load', () => resolve(true), {once:true}))",
        )
        // The host may resize the viewport meanwhile (the recovery UI changes its layout); only the document matters.
        val content = read(isViewportStable = false)
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
     * Element references require an unchanged document and viewport. Content capture tolerates a viewport
     * resize but still rejects another document, URL or any DOM mutation during the read.
     */
    private suspend fun read(isViewportStable: Boolean = true): JsonObject {
        val before = evaluate(GEOMETRY_SCRIPT)
        val content = evaluate(READ_SCRIPT).jsonObject
        val after = evaluate(GEOMETRY_SCRIPT)
        val isUnchanged = if (isViewportStable) after == before else after.documentState() == before.documentState()
        check(isUnchanged) { "Page changed while reading; read it again" }
        hasElements = true
        snapshot = Uuid.random().toString()
        geometry = before
        return JsonObject(content + ("snapshotId" to JsonPrimitive(snapshot)))
    }

    private suspend fun screenshot(request: BrowserRequest): BrowserResult {
        hasElements = false
        geometry = evaluate(GEOMETRY_SCRIPT)
        snapshot = Uuid.random().toString()
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
        check(evaluate(GEOMETRY_SCRIPT) == geometry) { "Page changed while capturing; capture it again" }
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
                        const r=e.getBoundingClientRect();
                        const x=r.x+r.width/2,y=r.y+r.height/2;
                        const hit=document.elementFromPoint(x,y);
                        return {x,y,isVisible:e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true}),
                            isTarget:hit===e||e.contains(hit)};
                    })()
                    """.trimIndent(),
                )
                    .jsonObject
                    .also { target ->
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
        try {
            mouse("mousePressed", point)
        } finally {
            withContext(NonCancellable) {
                withTimeout(INPUT_RELEASE_TIMEOUT) { mouse("mouseReleased", point) }
            }
        }
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
            withContext(NonCancellable) {
                withTimeout(INPUT_RELEASE_TIMEOUT) {
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
        try {
            mouse("mousePressed", args)
            mouse("mouseMoved", destination)
        } finally {
            withContext(NonCancellable) {
                withTimeout(INPUT_RELEASE_TIMEOUT) { mouse("mouseReleased", destination) }
            }
        }
        invalidate()
        return buildJsonObject { put("dragged", true) }
    }

    private suspend fun validate(args: JsonObject) {
        require(snapshot != null && args["snapshotId"]?.jsonPrimitive?.content == snapshot) {
            "Read or capture the page again"
        }
        require(evaluate(GEOMETRY_SCRIPT) == geometry) { "Page changed; read or capture it again" }
    }

    private suspend fun mouse(type: String, args: JsonObject, deltaY: Double? = null) {
        val x = args["x"]?.jsonPrimitive?.doubleOrNull ?: error("x required")
        val y = args["y"]?.jsonPrimitive?.doubleOrNull ?: error("y required")
        if (type != "mouseReleased") validatePoint(args)
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
        if (executionContext == null) {
            executionContext =
                call(
                    "Page.createIsolatedWorld",
                    buildJsonObject {
                        put("frameId", frame().getValue("id"))
                        put("worldName", worldName)
                    },
                )
                    .getValue("executionContextId")
        }
        val response =
            call(
                "Runtime.evaluate",
                buildJsonObject {
                    put("contextId", requireNotNull(executionContext))
                    put("expression", script)
                    put("returnByValue", true)
                    put("awaitPromise", true)
                },
            )
        check(response["exceptionDetails"] == null) { "Page changed or cannot be read" }
        return response["result"]?.jsonObject?.get("value") ?: error("Page returned no content")
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
        const val GEOMETRY_SCRIPT =
            """(() => {
            if (!window.__heartbeatObserver) {
                window.__heartbeatRevision=0; window.__heartbeatDocument=crypto.randomUUID?.() || `${'$'}{Date.now()}-${'$'}{Math.random()}`;
                window.__heartbeatObserver=new MutationObserver(()=>window.__heartbeatRevision++);
                window.__heartbeatObserver.observe(document,{subtree:true,childList:true,attributes:true,characterData:true});
            }
            return {document:window.__heartbeatDocument,url:location.href,width:innerWidth,height:innerHeight,x:scrollX,y:scrollY,revision:window.__heartbeatRevision};
        })()"""
        const val READ_SCRIPT =
            """(() => {
            const elements=Array.from(document.querySelectorAll('a[href],button,input,textarea,select,[role=button]'))
                .filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})}).slice(0,200);
            window.__heartbeatElements=elements;
            return {url:location.href,title:document.title,
                requiresUserAction:!!document.querySelector('input[type=password],iframe[src*="captcha"]'),content:(document.body?.innerText||'').slice(0,100000),
                elements:elements.map((e,id)=>({id,tag:e.tagName,label:(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,300),href:e.href||null}))};
        })()"""
    }
}

private fun JsonElement.documentState(): List<JsonElement?> = jsonObject.let { geometry ->
    listOf(geometry["document"], geometry["url"], geometry["revision"])
}

private fun ByteArray.pngDimension(offset: Int): Int = (offset until offset + Int.SIZE_BYTES).fold(0) { value, index ->
    (value shl Byte.SIZE_BITS) or this[index].toUByte().toInt()
}
