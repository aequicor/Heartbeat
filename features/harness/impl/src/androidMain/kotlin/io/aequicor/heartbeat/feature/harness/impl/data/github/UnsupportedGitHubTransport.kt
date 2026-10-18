package io.aequicor.heartbeat.feature.harness.impl.data.github

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubRequest
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubResponse
import io.aequicor.heartbeat.feature.harness.impl.domain.github.GitHubTransport

/** Mobile hosts do not execute gh or borrow a desktop's credentials. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedGitHubTransport : GitHubTransport {
    override val isAvailable = false
    override suspend fun call(request: GitHubRequest): GitHubResponse = GitHubResponse(0)
}
