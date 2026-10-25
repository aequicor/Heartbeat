package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Lets virtual sandbox tools reach origin: `push` of `sandbox_commit` and `fetch` of `sandbox_checkout` /
 * `sandbox_merge`. Off removes both arguments from the tool schemas and refuses them; local branch operations stay.
 */
internal val WorktreeSandboxRemoteEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "worktree_mode.sandbox_remote",
    "Песочницы: push и fetch веток в origin из инструментов песочницы",
)
