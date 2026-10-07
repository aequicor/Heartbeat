package io.aequicor.heartbeat.feature.harness.impl.domain.content

import io.aequicor.heartbeat.feature.harness.api.HarnessActivationRequest
import io.aequicor.heartbeat.feature.harness.api.ItemName
import io.aequicor.heartbeat.feature.harness.api.script.ScriptPrompts
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Templates belong to the same approved revision as this code instance. Pure rendering is also available during
 * evaluation; it never starts a session or reaches another harness. Atomic code replacement replaces its content
 * snapshot too. A retained facade cannot render after retirement, suspension or disable.
 */
internal class HarnessScriptPrompts(request: HarnessActivationRequest, private val access: HarnessInstanceAccess) :
    ScriptPrompts {
    private val content = HarnessContent(listOf(request.harness))

    override suspend fun render(name: ItemName, args: Map<String, String>): String {
        currentCoroutineContext().ensureActive()
        check(access.isRegistrationAllowed) { "Script template access was revoked" }
        return content.render(name.value, args)
    }
}
