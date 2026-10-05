package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlRequest
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult

/**
 * Least recently used drawings and syntax errors (both deterministic for a request), bounded by entry count and PNG
 * bytes; the newest entry always stays. Not thread-safe: the renderer guards it with its lock.
 */
internal class PlantUmlResultCache(private val maxEntries: Int, private val maxBytes: Long) {
    private val entries = LinkedHashMap<PlantUmlRequest, PlantUmlResult>()
    private var bytes = 0L

    operator fun get(request: PlantUmlRequest): PlantUmlResult? = entries.remove(
        request,
    )?.also { entries[request] = it }

    fun put(request: PlantUmlRequest, result: PlantUmlResult) {
        if (result !is PlantUmlResult.Image && result !is PlantUmlResult.SyntaxError) return
        entries.remove(request)?.let { bytes -= it.byteSize() }
        entries[request] = result
        bytes += result.byteSize()
        val iterator = entries.values.iterator()
        while ((entries.size > maxEntries || bytes > maxBytes) && entries.size > 1) {
            bytes -= iterator.next().byteSize()
            iterator.remove()
        }
    }

    private fun PlantUmlResult.byteSize(): Long = (this as? PlantUmlResult.Image)?.png?.size?.toLong() ?: 0L
}
