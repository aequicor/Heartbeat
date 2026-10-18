package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import kotlin.time.Clock

/** Runtime fixtures cannot perform external IO. */
internal fun githubTestFactory(origins: HarnessCallOrigins) = HarnessScriptGitHubFactory(
    object : GitHubTransport {
        override val isAvailable = false
        override suspend fun call(request: GitHubRequest): GitHubResponse = error(
            "GitHub is unavailable in runtime fixtures",
        )
    },
    Clock.System,
    origins,
    CommandToggles(),
)
