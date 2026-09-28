package io.aequicor.heartbeat.feature.aiengine.pi.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ReconcilesSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SwitchesModels
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Stable identity of the bundled desktop engine. */
public val PiEngineId: EngineId = EngineId("pi")

/** Exclusive namespace; a foreign CLI login is never accepted. */
public val PiAuthOwner: AuthOwnerId = AuthOwnerId("pi")

/** Enabled by default because the desktop distribution contains the engine. */
public val PiEnabled: FeatureToggle.Flag = FeatureToggle.Flag("ai.pi", "Встроенный движок Pi", default = true)

/**
 * Static metadata; installation and credentials are checked separately.
 * Pi accepts only managed API keys with a known revision for the exact Anthropic, OpenAI and Google public API
 * origins; model ids are `provider/native-id`. Desktop only (Windows, macOS).
 */
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
        RequestsPermissions.id,
    ),
    connectionMethods = listOf(
        ConnectionMethod.ApiKey(
            ConnectionMethodId("openai"),
            ProviderInfo(ProviderId("openai"), "OpenAI"),
            EndpointOrigin("https://api.openai.com"),
        ),
        ConnectionMethod.ApiKey(
            ConnectionMethodId("anthropic"),
            ProviderInfo(ProviderId("anthropic"), "Anthropic"),
            EndpointOrigin("https://api.anthropic.com"),
        ),
        ConnectionMethod.ApiKey(
            ConnectionMethodId("google"),
            ProviderInfo(ProviderId("google"), "Google"),
            EndpointOrigin("https://generativelanguage.googleapis.com"),
        ),
    ),
    isDefault = true,
    isLocalWorkspaceSupported = true,
)

/**
 * Pi-specific configuration of the current profile, beside the engine-neutral facade.
 * Credential routes are connected only through the facade's `EngineBindings`; this contract never
 * accepts credentials, starts Pi or exposes its adapter. Until the facade runtime implements `EngineBindings`,
 * Pi has no route in the running app and sessions fail with missing credentials.
 */
public interface PiEngine {
    /**
     * Resolves the opaque [workspace] to the existing local [directory] used by later Pi sessions of this profile.
     * Paths are never logged.
     *
     * @throws io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException with
     * `EngineFailure.Request(RequestFailureReason.Invalid)` when [directory] does not exist or is not a directory,
     * or with `EngineFailure.Engine(EngineFailureReason.UnsupportedCapability)` on platforms without Pi.
     */
    public suspend fun configureWorkspace(workspace: WorkspaceRef, directory: String)
}
