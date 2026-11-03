package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.toLLModel
import ai.koog.prompt.llm.LLMProvider
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibleProtocol
import io.aequicor.heartbeat.feature.aiengine.facade.api.isCompatibleOriginAllowed
import io.aequicor.heartbeat.feature.aiengine.koog.api.KoogProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@ContributesBinding(ProfileScope::class)
@Inject
internal class SdkKoogTransport(private val httpClient: HttpClient, private val platform: PlatformInfo) :
    KoogTransport {
    private val log = Log.tag("KoogTransport")

    override suspend fun open(
        provider: KoogProvider,
        key: String?,
        model: String?,
        origin: EndpointOrigin,
        basePath: String?,
    ): KoogClient = create(provider, key, model, origin, basePath)

    override suspend fun openIsolated(
        key: String?,
        origin: EndpointOrigin,
        basePath: String?,
        request: IsolatedKoogRequest,
    ): KoogClient? {
        // The JVM SLF4J payload guard is verified. Native/Android SDK logging needs its own proof before opt-in.
        if (platform.host == HostPlatform.Ios || platform.host == HostPlatform.Android) return null
        return create(request.provider, key, request.model, origin, basePath, request)
    }

    @Suppress("CognitiveComplexMethod") // Acquisition keeps all owned clients under one cleanup boundary.
    private suspend fun create(
        provider: KoogProvider,
        key: String?,
        model: String?,
        origin: EndpointOrigin,
        basePath: String?,
        isolated: IsolatedKoogRequest? = null,
    ): KoogClient {
        log.d { "Creating ${provider.name} transport" }
        val paths = KoogPaths(provider, AuthScope(provider.id, origin, basePath))
        currentCoroutineContext().ensureActive()
        val lease = isolated?.lease ?: OwnedKoogHttpLease()
        var isTransferred = false
        return withKoogCleanup(cleanup = {
            if (!isTransferred) withContext(NonCancellable) { lease.closeAndAwait() }
        }) {
            // Derived clients retain core network logging. Redirects cannot forward provider credentials.
            val base = httpClient.config {
                followRedirects = false
                if (isolated != null) {
                    install(HttpRequestRetry) { maxRetries = 0 }
                    install(isolatedKoogWire(isolated))
                }
            }
            lease.add(base)
            val usage = KoogUsageCapture()
            val ollama = if (isolated != null && provider == KoogProvider.Ollama) IsolatedOllamaCompletion() else null
            val stream = if (isolated == null && provider == KoogProvider.Ollama) OllamaStreamTerminal() else null
            val factory = OwnedKoogHttpFactory(KtorKoogHttpClient.Factory(baseClient = base), lease, ollama, stream)
                .withUsage(provider, usage)
            // Keep the intermediate configuration under the same owner until transfer or failed acquisition.
            val client = sdkClient(provider, key, model, paths, factory)
            val executor = MultiLLMPromptExecutor(mapOf(provider.llmProvider to client))
            val reasoning: suspend (List<String>) -> Map<String, List<String>>? = { models ->
                probeReasoning(httpClient, provider, key, models)
            }
            val result = KoogClient(
                executor,
                reasoning,
                usage,
                closeOwned = lease::close,
                awaitClosed = lease::closeAndAwait,
                observedFinishReason = { ollama?.reason ?: stream?.reason },
            ) {
                if (client is OllamaClient) client.getModels().map { it.toLLModel() } else client.models()
            }
            currentCoroutineContext().ensureActive()
            isTransferred = true
            result
        }
    }
}

internal val KoogProvider.llmProvider: LLMProvider
    get() = when (this) {
        KoogProvider.OpenAI, KoogProvider.AlibabaQwen, KoogProvider.OpenAICompatible -> LLMProvider.OpenAI
        KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> LLMProvider.Anthropic
        KoogProvider.Ollama -> LLMProvider.Ollama
    }

/** SDK-relative request paths; compatible routes use the user's base path, vendor routes the SDK defaults. */
private class KoogPaths(provider: KoogProvider, scope: AuthScope) {
    init {
        // Credentials follow the validated source scope rather than SDK defaults.
        val origin = scope.origin
        require(if (provider.isOriginEditable) isCompatibleOriginAllowed(origin) else origin == provider.origin) {
            "Origin ${origin.value} is not allowed for ${provider.name}"
        }
        require(provider.isOriginEditable || scope.basePath == null) { "${provider.name} has a fixed API path" }
    }

    val baseUrl: String = scope.origin.value
    private val openAiPrefix =
        if (provider == KoogProvider.OpenAICompatible) CompatibleProtocol.OpenAI.apiBase(scope) else "/v1"
    private val anthropicPrefix =
        if (provider == KoogProvider.AnthropicCompatible) CompatibleProtocol.Anthropic.apiBase(scope) else ""

    fun openAi(endpoint: String): String = "$openAiPrefix/$endpoint".trimStart('/')

    fun anthropic(endpoint: String): String = "$anthropicPrefix/v1/$endpoint".trimStart('/')

    /** Maps [model] to itself so arbitrary provider ids pass the SDK's model version lookup. */
    fun anthropicSettings(baseUrl: String, provider: KoogProvider, model: String?): AnthropicClientSettings =
        AnthropicClientSettings(
            modelVersionsMap = model?.let {
                mapOf(provider.textModel(it) to it, provider.textModel(it, attachments = true) to it)
            } ?: AnthropicClientSettings().modelVersionsMap,
            baseUrl = baseUrl,
            messagesPath = anthropic("messages"),
            modelsPath = anthropic("models"),
        )
}

private fun KoogHttpClient.Factory.withUsage(provider: KoogProvider, usage: KoogUsageCapture): KoogHttpClient.Factory =
    if (provider.llmProvider == LLMProvider.Anthropic) UsageKoogHttpFactory(this, usage) else this

private fun sdkClient(
    provider: KoogProvider,
    key: String?,
    model: String?,
    paths: KoogPaths,
    factory: KoogHttpClient.Factory,
): LLMClient = when (provider) {
    KoogProvider.OpenAI, KoogProvider.OpenAICompatible -> OpenAILLMClient(
        apiKey = requireNotNull(key),
        settings = OpenAIClientSettings(
            baseUrl = paths.baseUrl,
            chatCompletionsPath = paths.openAi("chat/completions"),
            modelsPath = paths.openAi("models"),
        ),
        httpClientFactory = factory,
    )

    KoogProvider.AlibabaQwen -> AlibabaKoogClient(
        apiKey = requireNotNull(key),
        settings = OpenAIClientSettings(
            baseUrl = paths.baseUrl,
            chatCompletionsPath = "compatible-mode/v1/chat/completions",
            modelsPath = "compatible-mode/v1/models",
        ),
        httpClientFactory = factory,
    )

    KoogProvider.Anthropic, KoogProvider.AnthropicCompatible -> AnthropicLLMClient(
        apiKey = requireNotNull(key),
        settings = paths.anthropicSettings(paths.baseUrl, provider, model),
        httpClientFactory = factory,
    )

    KoogProvider.Ollama -> OllamaClient(httpClientFactory = factory, baseUrl = paths.baseUrl)
}
