package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsImages
import io.aequicor.heartbeat.feature.aiengine.facade.api.AcceptsResources
import io.aequicor.heartbeat.feature.aiengine.facade.api.AppliesTrustLevels
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ChangesSessionConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.RestoresSessionTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextRevision
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels

internal fun piSessionFeatures(
    session: PiSession,
    journal: SessionHistory,
    support: () -> PromptInputSupport,
): EngineFeatures = PiFeatures(
    listOf(
        AcceptsImages to object : AcceptsImages {
            override val mediaTypes: Set<String> get() = support().imageMediaTypes
        },
        AcceptsResources to object : AcceptsResources {
            override val mediaTypes: Set<String> get() = support().resourceMediaTypes
        },
        SendsPrompts to session,
        CancelsTurns to session,
        SwitchesModels to session,
        ReconcilesSession to session,
        RestoresSessionTurns to session.turnRecovery,
        RequestsPermissions to session,
        AppliesTrustLevels to session,
        ChangesSessionConfiguration to session,
        SessionHistory to journal,
        SessionContextUsage to session.contextUsage,
        SessionContextRevision to session.contextRevision,
    ),
)
