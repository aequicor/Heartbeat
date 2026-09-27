package io.aequicor.heartbeat.feature.aiengine.pi.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory

/** Stable identity of the bundled desktop engine. */
public val PiEngineId: EngineId = EngineId("pi")

/** Exclusive namespace; a foreign CLI login is never accepted. */
public val PiAuthOwner: AuthOwnerId = AuthOwnerId("pi")

/** Enabled by default because the desktop distribution contains the engine. */
public val PiEnabled: FeatureToggle.Flag = FeatureToggle.Flag("ai.pi", "Встроенный движок Pi", default = true)

/** Static metadata; installation and credentials are checked separately. */
public val PiDescriptor: EngineDescriptor = EngineDescriptor(
    id = PiEngineId,
    title = "Pi",
    family = EngineFamily.BuiltIn,
    platforms = setOf(EnginePlatform.DesktopWindows, EnginePlatform.DesktopMacOs),
    toggle = PiEnabled,
    declaredFeatures = setOf(
        CreatesSessions.id,
        SendsPrompts.id,
        CancelsTurns.id,
        SwitchesModels.id,
        ReconcilesSession.id,
        SessionHistory.id,
    ),
    isDefault = true,
)

/**
 * Profile-owned Pi adapter. The bundle exposes it as an EngineRegistration and as this configuration entry point.
 * Supports managed API keys for the exact Anthropic, OpenAI and Google public API origins.
 * Secret ids refer to existing profile vault keys; no credential values cross this API.
 * Configure a binding with a Known source revision before discovery or runtime creation.
 * Model ids are provider/native-id pairs returned by discoverModels. Live sessions expose text prompts,
 * cancellation, model switching, recovery and a replayable transcript. Stored-session resume and image input
 * are not advertised. Native transcripts survive handle closure and are removed by profile wipe.
 * Native CLI OAuth, helpers, arbitrary endpoints and mobile execution are not advertised.
 */
public interface PiEngine : EngineFactory {
    /** Persists an explicit binding/source route. Replacing a route retires its existing runtimes. */
    public suspend fun configure(binding: EngineBindingId, source: AuthSource.ManagedKey)

    /** Resolves an opaque workspace to an existing local directory. Paths are never logged. */
    public suspend fun configureWorkspace(workspace: WorkspaceRef, directory: String)
}
