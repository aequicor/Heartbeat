package io.aequicor.heartbeat.feature.harness.impl.domain.services

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.script.ScriptData
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataSnapshot
import io.aequicor.heartbeat.feature.harness.api.script.ScriptDataWriteResult
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement

/** A retained reference grants no access after its approved activation is retired. */
internal class HarnessScriptData(
    private val owner: HarnessId,
    private val access: HarnessInstanceAccess,
    private val storage: HarnessDataStorage,
) : ScriptData {
    override suspend fun read(key: String): ScriptDataSnapshot {
        val context = currentCoroutineContext()
        return storage.read(owner, key) {
            context.ensureActive()
            check(access.isActive) { "Script data access is not active" }
        }
    }

    override suspend fun write(key: String, value: JsonElement?, expectedRevision: Long): ScriptDataWriteResult {
        val context = currentCoroutineContext()
        return storage.write(owner, key, value, expectedRevision) {
            context.ensureActive()
            check(access.isActive) { "Script data access is not active" }
        }
    }

    override suspend fun update(key: String, transform: (JsonElement?) -> JsonElement?): ScriptDataSnapshot {
        var current = read(key)
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val result = write(key, transform(current.value), current.revision)) {
                is ScriptDataWriteResult.Written -> return result.snapshot
                is ScriptDataWriteResult.Conflict -> current = result.current
            }
        }
    }
}
