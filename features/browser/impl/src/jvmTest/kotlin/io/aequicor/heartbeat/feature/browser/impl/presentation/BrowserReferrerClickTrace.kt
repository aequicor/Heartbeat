package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

/** Keeps the last callback after awaitTitle drains the queue; it does not alter the probe's waits. */
internal class ReferrerProbeStates : LinkedBlockingQueue<BrowserViewState>() {
    val latest = AtomicReference<BrowserViewState?>()

    override fun offer(state: BrowserViewState): Boolean {
        latest.set(state)
        return super.offer(state)
    }
}

/**
 * Observes real input without dispatching events, preventing defaults, retrying, or waiting for render frames.
 * Reports only failed local-fixture cases. The source document keeps its mouse events when the host cancels
 * a linked navigation to open a tab; missing events distinguish input delivery from navigation-policy failures.
 */
internal class BrowserReferrerClickTrace(
    private val engine: DesktopBrowserEngine,
    private val fork: Boolean,
    private val policy: String,
    private val source: String,
) {
    private var before: JsonElement = JsonNull
    private val inputs = mutableListOf<JsonObject>()

    suspend fun arm() {
        evaluate(
            """
            (() => {
                window.__referrerClickEvents=[];
                for (const type of ['pointerdown','pointerup','mousedown','mouseup','click','auxclick']) {
                    window.addEventListener(type,event=>{
                        const target=event.target;
                        const record={type:event.type,trusted:event.isTrusted,x:event.clientX,y:event.clientY,
                            target:target?.tagName,href:target?.closest?.('a')?.href ?? null,
                            prevented:event.defaultPrevented,time:performance.now()};
                        if (window.__referrerClickEvents.length<24) window.__referrerClickEvents.push(record);
                        queueMicrotask(()=>{record.preventedAfterDispatch=event.defaultPrevented;});
                    },{capture:true});
                }
                return true;
            })()
            """.trimIndent(),
        )
        before = evaluate(SNAPSHOT)
    }

    suspend fun dispatch(method: String, arguments: JsonObject): String {
        if (method != "Input.dispatchMouseEvent") return engine.devTools(method, arguments)
        inputs += buildJsonObject {
            put("method", method)
            put("arguments", arguments)
        }
        return engine.devTools(method, arguments)
    }

    /** Diagnostic failures are suppressed on the existing assertion; they cannot replace the test's outcome. */
    fun attachFailure(failure: Throwable, state: BrowserViewState?) {
        val observation = runCatching { runBlocking { withTimeout(5_000) { evaluate(SNAPSHOT) } } }
        val observationFailure = observation.exceptionOrNull()
        val after = if (observationFailure == null) observation.getOrThrow() else JsonNull
        failure.attachDiagnostic(observationFailure)
        val diagnostic = runCatching {
            val report = buildJsonObject {
                put("fork", fork)
                put("policy", policy)
                put("source", source)
                put("before", before)
                put("after", after)
                put("input", buildJsonArray { inputs.forEach { add(it) } })
                put("lastState", state?.toString())
            }
            val output = File("build/native-ui/referrer-fork-$fork-$policy.json")
            check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
            output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
            val snapshot = after as? JsonObject
            val events = (snapshot?.get("events") as? JsonArray)?.map { event ->
                val detail = event.jsonObject
                "${detail["type"]}:${detail["target"]}:trusted=${detail["trusted"]}"
            }
            failure.addSuppressed(
                AssertionError(
                    "Referrer click trace: url=${snapshot?.get("url")}, events=$events, " +
                        "lastState=$state; report=${output.absolutePath}",
                ),
            )
        }.exceptionOrNull()
        failure.attachDiagnostic(diagnostic)
    }

    private fun Throwable.attachDiagnostic(diagnostic: Throwable?) {
        if (diagnostic != null && diagnostic !== this) addSuppressed(diagnostic)
    }

    private suspend fun evaluate(expression: String): JsonElement {
        val response = Json.parseToJsonElement(
            engine.devTools(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("returnByValue", true)
                    put("awaitPromise", true)
                },
            ),
        ).jsonObject
        check(response["exceptionDetails"] == null) { "Referrer diagnostic could not evaluate the current document" }
        return response.getValue("result").jsonObject.getValue("value")
    }

    private companion object {
        val SNAPSHOT = """
            (() => {
                const link=document.querySelector('a');
                const rect=link?.getBoundingClientRect();
                const x=rect ? rect.x+rect.width/2 : null, y=rect ? rect.y+rect.height/2 : null;
                const hit=rect ? document.elementFromPoint(x,y) : null;
                return {url:location.href,title:document.title,readyState:document.readyState,
                    visibility:document.visibilityState,focused:document.hasFocus(),
                    viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,x:scrollX,y:scrollY},
                    link:link ? {href:link.href,rect:{x:rect.x,y:rect.y,width:rect.width,height:rect.height},
                        center:{x,y},hit:{tag:hit?.tagName,href:hit?.closest?.('a')?.href ?? null}} : null,
                    events:window.__referrerClickEvents ?? [],time:performance.now()};
            })()
        """.trimIndent()
    }
}
