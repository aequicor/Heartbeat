package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.ClaudeCandidates
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.ClaudeCompatibility
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.UnsupportedClaudeCandidates
import io.aequicor.heartbeat.feature.aiengine.claude.impl.data.runtime.UnsupportedClaudeCompatibility

@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedPlatformClaudeCompatibility : ClaudeCompatibility by UnsupportedClaudeCompatibility

@ContributesBinding(ProfileScope::class)
@Inject
internal class UnsupportedPlatformClaudeCandidates : ClaudeCandidates by UnsupportedClaudeCandidates
