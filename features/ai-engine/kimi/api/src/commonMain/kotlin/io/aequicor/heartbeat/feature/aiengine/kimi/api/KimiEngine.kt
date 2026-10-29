package io.aequicor.heartbeat.feature.aiengine.kimi.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId

/** Stable identities of the Kimi Code ACP adapter. OAuth material remains owned by Kimi Code CLI. */
public object KimiEngine {
    public val Id: EngineId = EngineId("kimi")
    public val AuthOwner: AuthOwnerId = AuthOwnerId("kimi.code")
    public val AuthSource: AuthSourceId = AuthSourceId("kimi.local")
    public val AuthLocation: AuthLocationId = AuthLocationId("kimi.local")
    public val AuthContext: AuthContextKey = AuthContextKey("kimi.code")
    public val SessionSource: SessionSourceId = SessionSourceId("kimi.local")
    public val Enabled: FeatureToggle.Flag = FeatureToggle.Flag(
        "ai.kimi",
        "Движок Kimi CLI",
        default = true,
    )
}

/** Process configuration. Values are never executed through a shell or logged. */
public data class KimiConfiguration(
    public val executable: String = "kimi",
    public val workingDirectory: String? = null,
) {
    init {
        require(executable.isNotBlank())
        require(workingDirectory == null || workingDirectory.isNotBlank())
    }

    override fun toString(): String = "KimiConfiguration(***)"
}
