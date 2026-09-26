package io.aequicor.heartbeat.core.navigation.impl

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.navigation.ResultContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Pending results of one navigation tree: `requester entry path → contract name → result JSON`.
 * Retained by the root's InstanceKeeper and saved through its StateKeeper. Main thread only.
 */
internal class ResultStore(restored: SavedResults?) : InstanceKeeper.Instance {

    private val log = Log.tag(LOG_TAG)
    private val pending = MutableStateFlow(restored?.results.orEmpty())

    fun <R : Any> put(requester: String, contract: ResultContract<R>, result: R) {
        val json = ResultJson.encodeToString(contract.serializer, result)
        pending.update { it + (requester to (it[requester].orEmpty() + (contract.name to json))) }
        log.i { "result ${contract.name} -> $requester" }
    }

    /**
     * Takes results of [contract] for [requester] out of the store and emits them. Removed before emitting:
     * collectors like `first()` cancel right after the emission, and the result must not be delivered twice.
     */
    fun <R : Any> results(requester: String, contract: ResultContract<R>): Flow<R> = flow {
        pending.collect { all ->
            val json = all[requester]?.get(contract.name) ?: return@collect
            remove(requester, contract.name)
            decode(contract, json)?.let { emit(it) }
        }
    }

    /** Drops results of the entry at [path] and of every entry below it (the entry is destroyed for good). */
    fun dropTree(path: String) {
        pending.update { all -> all.filterKeys { it != path && !it.startsWith("$path/") } }
        log.d { "results of $path dropped" }
    }

    fun snapshot(): SavedResults = SavedResults(pending.value)

    private fun remove(requester: String, contract: String) {
        pending.update { all ->
            val left = all[requester].orEmpty() - contract
            if (left.isEmpty()) all - requester else all + (requester to left)
        }
        log.d { "result $contract taken by $requester" }
    }

    private fun <R : Any> decode(contract: ResultContract<R>, json: String): R? = try {
        ResultJson.decodeFromString(contract.serializer, json)
    } catch (e: SerializationException) {
        log.w(e) { "dropped unreadable result ${contract.name}" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "dropped invalid result ${contract.name}" }
        null
    }
}

@Serializable
internal data class SavedResults(val results: Map<String, Map<String, String>>)

private val ResultJson = Json { ignoreUnknownKeys = true }
