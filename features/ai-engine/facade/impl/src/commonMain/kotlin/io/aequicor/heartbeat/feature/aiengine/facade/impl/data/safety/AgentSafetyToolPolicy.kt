package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety

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

internal interface AgentSafetyIsolation {
    val isActive: Boolean
    fun contains(session: SessionRef): Boolean
    fun restrict(session: SessionRef, nativeTools: List<String>)
    fun release(session: SessionRef)
}

/**
 * Tool-free isolation applies before the first service prompt, including ungated Codex tools (its adapter cold-resumes
 * with the policy before submitting). There is no project, detached hosted tools or inherited harness selection.
 * A session whose terminal state is unknown retains its restrictions until observed completion or profile closure.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<AgentSafetyIsolation>())
@ContributesIntoSet(ProfileScope::class, binding = binding<AgentToolPolicyProvider>())
@Inject
internal class AgentSafetyToolPolicy :
    AgentToolPolicyProvider,
    AgentSafetyIsolation {
    private val log = Log.tag("AgentSafetyPolicy")
    private val sessions = MutableStateFlow<Map<SessionRef, ToolPolicy>>(emptyMap())

    override val isIsolation: Boolean = true

    override val isActive: Boolean get() = sessions.value.isNotEmpty()

    override fun contains(session: SessionRef): Boolean = session in sessions.value

    override fun restrict(session: SessionRef, nativeTools: List<String>) {
        sessions.update {
            it + (
                session to ToolPolicy(
                    native = nativeTools.associateWith { ToolSwitch.Off },
                    isToolFree = true,
                )
            )
        }
        log.d { "Restricted tools of a text-only service session" }
    }

    override fun release(session: SessionRef) {
        sessions.update { it - session }
        log.d { "Released tool restrictions of a completed service session" }
    }

    override suspend fun policy(scope: ToolPolicyScope): ToolPolicy? = sessions.value[scope.session]
}
