package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits

/**
 * Profile-lifetime names retain their first published declaration, including after deletion or slug reuse.
 * All calls belong to the runtime publication mutex. Validation never mutates this ledger; only a successfully
 * published candidate commits. Active quotas exclude the replaced instance and already disposed registrations.
 */
internal class HarnessToolDeclarations {
    private val published = mutableMapOf<String, AgentToolSpec>()
    private val log = Log.tag("HarnessToolDeclarations")

    @HighFrequency
    fun accepts(candidate: HarnessInstance, current: Collection<HarnessInstance>): Boolean {
        log.v { "validate candidate tool declarations" }
        val declarations = candidate.scriptRegistrations()?.sealTools().orEmpty()
        val others = current.asSequence().filter {
            it.request.item.id != candidate.request.item.id || it.request.harness.id != candidate.request.harness.id
        }.filter { it.isActive }.flatMap { instance ->
            instance.scriptRegistrations()?.tools().orEmpty().asSequence().filter { it.callback.isActive }.map {
                instance.request.harness.id to it.specification
            }
        }
        val entries = others.toList()
        val names = entries.map { it.second.name } + declarations.map { it.name }
        val count = entries.count { it.first == candidate.request.harness.id } + declarations.size
        return count <= HarnessLimits.SCRIPT_TOOLS && names.distinct().size == names.size &&
            declarations.all { published[it.name]?.let { previous -> previous == it } != false }
    }

    @HighFrequency
    fun published(instance: HarnessInstance) {
        log.v { "commit published tool declarations" }
        instance.scriptRegistrations()?.sealTools().orEmpty().forEach { published[it.name] = it }
    }
}

private fun HarnessInstance.scriptRegistrations(): HarnessScriptRegistrations? =
    (runtimeContext as? HarnessScriptContext)?.registrations
