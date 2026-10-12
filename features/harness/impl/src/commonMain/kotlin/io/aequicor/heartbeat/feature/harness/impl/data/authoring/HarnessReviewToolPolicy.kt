package io.aequicor.heartbeat.feature.harness.impl.data.authoring

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal interface HarnessReviewIsolation {
    val isActive: Boolean
    fun restrict(session: SessionRef, nativeTools: List<String>)
    fun release(session: SessionRef)
}

/**
 * Native Off applies before the first review prompt, including ungated Codex tools (its adapter cold-resumes
 * with the policy before submitting). There is no project, detached hosted tools or inherited harness selection.
 * A session whose terminal state is unknown retains its restrictions until observed completion or profile closure.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<HarnessReviewIsolation>())
@ContributesIntoSet(ProfileScope::class, binding = binding<AgentToolPolicyProvider>())
@Inject
internal class HarnessReviewToolPolicy :
    AgentToolPolicyProvider,
    HarnessReviewIsolation {
    private val log = Log.tag("HarnessReviewPolicy")
    private val sessions = MutableStateFlow<Map<SessionRef, ToolPolicy>>(emptyMap())

    override val isActive: Boolean get() = sessions.value.isNotEmpty()

    override fun restrict(session: SessionRef, nativeTools: List<String>) {
        sessions.update { it + (session to ToolPolicy(native = nativeTools.associateWith { ToolSwitch.Off })) }
        log.d { "Restricted tools of a script review session" }
    }

    override fun release(session: SessionRef) {
        sessions.update { it - session }
        log.d { "Released tool restrictions of a completed review session" }
    }

    override suspend fun policy(scope: ToolPolicyScope): ToolPolicy? = sessions.value[scope.session]
}
