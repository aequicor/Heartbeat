package io.aequicor.heartbeat.feature.aiengine.connections.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthLocationId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthOwnerId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo

internal object TestData {
    val openAi = ProviderInfo(ProviderId("openai"), "OpenAI")
    val apiKey = ConnectionMethod.ApiKey(
        ConnectionMethodId("openai.key"),
        openAi,
        EndpointOrigin("https://api.openai.com"),
    )
    val local = ConnectionMethod.NoAuth(
        ConnectionMethodId("ollama"),
        ProviderInfo(ProviderId("ollama"), "Ollama"),
        EndpointOrigin("http://localhost:11434"),
    )
    val cli = ConnectionMethod.CliLogin(
        ConnectionMethodId("codex.cli"),
        openAi,
        EndpointOrigin("https://chatgpt.com"),
        AuthOwnerId("codex"),
        AuthLocationId("codex.local"),
    )

    val koog = engine("koog", listOf(apiKey, local))
    val codex = engine("codex", listOf(cli))
    val mobileOnly = engine("pi", listOf(apiKey), EngineAvailability.UnsupportedPlatform)
    val noMethods = engine("acp", emptyList())

    val connection = NewConnection(EngineBindingId("binding-1"), AuthSourceId("source-1"))

    fun model(id: String, binding: EngineBindingId = connection.binding): ModelInfo =
        ModelInfo(EngineTarget(koog.descriptor.id, binding, ModelId(id)), id)

    fun engine(
        id: String,
        methods: List<ConnectionMethod>,
        availability: EngineAvailability = EngineAvailability.Unknown,
    ): EngineInfo = EngineInfo(
        EngineDescriptor(
            EngineId(id),
            id,
            EngineFamily.MultiProvider,
            setOf(EnginePlatform.DesktopWindows),
            AiEngines,
            connectionMethods = methods,
        ),
        availability,
        bindings = emptyList(),
    )
}
