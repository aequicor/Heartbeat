package io.aequicor.heartbeat.feature.aiengine.koog.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CancelsTurns
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethod
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConnectionMethodId
import io.aequicor.heartbeat.feature.aiengine.facade.api.CreatesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDescriptor
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFamily
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.PageRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestsPermissions
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumesSessions
import io.aequicor.heartbeat.feature.aiengine.facade.api.SendsPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHistory
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionQuery
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineSessionSource
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogAuthOwner
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogAutoApprove
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogCodingTools
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineAdapter
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogEngineId
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogReasoningCatalogEnabled
import io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime.KoogSessionSource

/** App-wide toggle declaration; registrations themselves are owned by profiles. */
@BindingContainer
@ContributesTo(AppScope::class)
public object KoogToggleBindings {
    /** Registers the disabled-by-default engine switch. */
    @Provides
    @IntoSet
    public fun toggle(): FeatureToggle<*> = KoogEngineEnabled

    /** Registers the disabled-by-default reasoning catalog switch. */
    @Provides
    @IntoSet
    public fun reasoningCatalog(): FeatureToggle<*> = KoogReasoningCatalogEnabled

    /** Registers the disabled-by-default Desktop coding tools switch. */
    @Provides
    @IntoSet
    public fun codingTools(): FeatureToggle<*> = KoogCodingTools

    /** Registers the approval mode of coding tools, automatic by default. */
    @Provides
    @IntoSet
    public fun autoApprove(): FeatureToggle<*> = KoogAutoApprove
}

/** Lazy registration: descriptor lookup never resolves secrets, creates a client or reads storage. */
@BindingContainer
@ContributesTo(ProfileScope::class)
public object KoogBindings {
    /** One multi-provider engine and one profile-native history store. */
    @Provides
    @IntoSet
    public fun registration(engine: Lazy<KoogEngineAdapter>): EngineRegistration = EngineRegistration(
        descriptor = EngineDescriptor(
            KoogEngineId,
            "Koog",
            EngineFamily.MultiProvider,
            EnginePlatform.entries.toSet(),
            KoogEngineEnabled,
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
                    ConnectionMethodId(KoogProvider.AlibabaQwen.id.value),
                    ProviderInfo(KoogProvider.AlibabaQwen.id, "Alibaba Qwen (Token Plan)"),
                    KoogProvider.AlibabaQwen.origin,
                ),
                CompatibleProtocol.OpenAI.method,
                CompatibleProtocol.Anthropic.method,
                ConnectionMethod.NoAuth(
                    ConnectionMethodId("ollama"),
                    ProviderInfo(ProviderId("ollama"), "Ollama"),
                    EndpointOrigin("http://localhost:11434"),
                    isOriginEditable = false,
                ),
            ),
            declaredFeatures = setOf(
                CreatesSessions.id,
                SendsPrompts.id,
                CancelsTurns.id,
                SessionHistory.id,
                RequestsPermissions.id,
                ResumesSessions.id,
                ListsSessions.id,
            ),
        ),
        authOwner = KoogAuthOwner,
        factory = lazy { engine.value },
        sessionSources = listOf(object : EngineSessionSource {
            override val source = KoogSessionSource
            override val discovery = object : ListsSessions {
                override suspend fun page(query: SessionQuery, request: PageRequest) = engine.value.page(query, request)
            }
            override suspend fun get(ref: SessionRef) = engine.value.get(ref)
        }),
    )
}
