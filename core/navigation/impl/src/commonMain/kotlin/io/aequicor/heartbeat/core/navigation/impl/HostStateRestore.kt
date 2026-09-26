package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.essenty.statekeeper.SerializableContainer
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** An incompatible saved host restarts from its initial routes and discards its orphaned result addresses. */
internal fun <T : Any> HostNode.restoreState(container: SerializableContainer, serializer: KSerializer<T>): T? = try {
    container.consume(serializer)
} catch (e: IllegalArgumentException) {
    // Deserialization messages and causes may include route payloads. Report only the error type.
    restoreLog.w(
        SerializationException("Incompatible navigation state: ${e::class.simpleName ?: "IllegalArgumentException"}"),
    ) {
        "$path: saved navigation discarded, restoring initial routes"
    }
    tree.results.dropTree(path)
    null
}

private val restoreLog = Log.tag(LOG_TAG)
