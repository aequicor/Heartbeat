package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAutomaticAuthorization
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewElement
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewFailure
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentResourcePolicy
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import io.aequicor.heartbeat.feature.serviceasui.impl.data.uiWebConfiguration
import io.aequicor.heartbeat.feature.serviceasui.impl.data.uiWebPolicyInstruction
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebConfiguration
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Disposable visual checks of HTML drafts. This contribution has no library, owner methods, chat host or persistent
 * storage; the browser preview contract cannot grant a page bridge. Availability is checked again at execution
 * because an engine can retain declarations after a flag changes.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ServiceUiPreviewTools(
    private val previews: BrowserDocumentPreviews,
    private val toggles: FeatureToggles,
    platform: PlatformInfo,
    private val safety: ServiceUiToolSafety,
) : AgentToolContribution {
    private val isDesktop = platform.host in setOf(HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.Linux)
    private val configuration = toggles.uiWebConfiguration(isDesktop)
    override val group: String = ServiceUiTools.GROUP
    override val title: String = "Интерфейсы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = listOf(ServiceUiPreviewSpec).toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) listOf(ServiceUiPreviewSpec) else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String =
        if (isEnabled()) INSTRUCTIONS + "\n" + toggles.uiWebPolicyInstruction() else ""

    override suspend fun instructions(scope: AgentToolScope): String {
        val declared = scope.declared
        return if (declared == null || ServiceUiTools.PREVIEW in declared) instructions(scope.workspace) else ""
    }

    override suspend fun automaticAuthorization(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolAutomaticAuthorization? {
        val current = configuration.first()
        if (!current.isEnabled) return AgentToolAutomaticAuthorization.Denied("WebView preview is unavailable")
        if (current.resourcePolicy == BrowserDocumentResourcePolicy.Isolated) return null
        val parsed = previewArguments(arguments)
        if (parsed is UiPreviewArguments.Invalid) return AgentToolAutomaticAuthorization.Denied(parsed.message)
        return safety.authorize(context, previewSafety(context, arguments, current))
    }

    override suspend fun approval(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
    ): AgentToolApproval = when (val parsed = previewArguments(arguments)) {
        is UiPreviewArguments.Invalid ->
            AgentToolApproval(ServiceUiTools.PREVIEW, "Preview an HTML draft", parsed.message, "invalid")

        is UiPreviewArguments.Valid -> previewSafety(context, arguments, configuration.first()).approval
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (name != ServiceUiTools.PREVIEW) return failure("Unknown preview tool")
        val current = configuration.first()
        if (!current.isEnabled) return failure("WebView preview requires Desktop and enabled interface web documents")
        val input = when (val parsed = previewArguments(arguments)) {
            is UiPreviewArguments.Valid -> parsed
            is UiPreviewArguments.Invalid -> return failure(parsed.message)
        }
        if (current.resourcePolicy == BrowserDocumentResourcePolicy.ExternalAssets &&
            !safety.isAuthorized(context, previewSafety(context, arguments, current))
        ) {
            return failure("Automatic safety review is missing or settings changed; request the preview again")
        }
        val configured = input.copy(request = input.request.copy(resourcePolicy = current.resourcePolicy))
        val result = previewWithConfiguration(configuration, current) { previews.preview(configured.request) }
            ?: return failure("WebView settings changed during preview; retry with the current settings")
        return when (result) {
            is BrowserDocumentPreviewResult.Rendered -> rendered(result, configured)

            BrowserDocumentPreviewResult.Unavailable -> failure("WebView preview is unavailable on this host")

            is BrowserDocumentPreviewResult.Failure ->
                failure("WebView preview failed: ${result.reason.name}. ${result.reason.recovery()}")
        }
    }

    private fun previewSafety(
        context: AgentToolContext,
        arguments: JsonObject,
        configuration: UiWebConfiguration,
    ): UiToolSafetyRequest = uiToolSafetyRequest(
        context,
        AgentToolApproval(ServiceUiTools.PREVIEW, "Preview an HTML draft"),
        arguments,
        buildJsonObject {
            put("isEnabled", configuration.isEnabled)
            put("bridge", false)
            put("persistence", false)
        },
        resourcePolicy = configuration.resourcePolicy,
    )

    private suspend fun isEnabled(): Boolean = isDesktop && toggles.get(ServiceUiEnabled) && toggles.get(ServiceUiWeb)

    private fun rendered(
        result: BrowserDocumentPreviewResult.Rendered,
        input: UiPreviewArguments.Valid,
    ): AgentToolResult {
        val isScreenshotIncluded = input.isScreenshotIncluded
        val report = buildJsonObject {
            put("scope", "disposable WebView draft; owner bridge disabled; no saved interface changed")
            put("resourcePolicy", input.request.resourcePolicy.name)
            put("theme", if (input.request.isDark) "dark" else "light")
            put("fontScale", input.request.fontScale)
            putJsonObject("viewport") {
                put("width", result.metrics.viewportWidth)
                put("height", result.metrics.viewportHeight)
            }
            putJsonObject("content") {
                put("width", result.metrics.contentWidth)
                put("height", result.metrics.contentHeight)
                put("horizontalOverflow", result.metrics.hasHorizontalOverflow)
            }
            putJsonArray("issues") {
                result.issues.forEach { issue ->
                    add(
                        buildJsonObject {
                            put("kind", issue.kind.name)
                            put("element", issue.element)
                        },
                    )
                }
            }
            putJsonArray("samples") {
                result.samples.forEach { sample -> add(sample.json()) }
            }
            put("issuesTruncated", result.issuesTruncated)
            put("imageIncluded", isScreenshotIncluded)
            put("htmlBytes", input.request.html.encodeToByteArray().size)
            put("htmlLimitBytes", UiSpecLimits.HTML_BYTES)
            put(
                "verification",
                (if (isScreenshotIncluded) "Inspect the image yourself. " else "No image delivered. ") +
                    "Bounded style samples are not a full DOM or contrast audit; " +
                    "text diagnostics cannot establish visual or aesthetic quality. " +
                    "This preview does not test native JSON nodes, owner methods, persistence " +
                    "or the complete host shell.",
            )
        }
        val images = if (isScreenshotIncluded) listOf(AgentToolImage("image/png", result.imageBase64)) else emptyList()
        return AgentToolResult(report.toString(), images = images)
    }

    private companion object {
        const val INSTRUCTIONS = "Use ui_preview with an HTML draft to check it before ui_put. " +
            "It uses the host renderer and current Safe Mode setting without owner methods " +
            "or persistence. Default text diagnostics work with text-only models and do not verify aesthetic " +
            "quality. Set includeScreenshot=true only for models supporting image tool results, then inspect " +
            "the image. Supply realistic sample data, check narrow/wide and light/dark variants, fix defects and " +
            "preview again. This does not render native JSON or test the saved app's live owner flow."
    }
}

private fun failure(message: String): AgentToolResult = AgentToolResult(message, isError = true)

private fun BrowserDocumentPreviewElement.json(): JsonObject = buildJsonObject {
    put("element", element)
    put("tag", tag)
    put("x", x)
    put("y", y)
    put("width", width)
    put("height", height)
    put("fontFamily", fontFamily)
    put("fontSize", fontSize)
    put("lineHeight", lineHeight)
    put("color", color)
    put("backgroundColor", backgroundColor)
}

/** Recovery hint for the agent; the reason itself never carries page or native text. */
private fun BrowserDocumentPreviewFailure.recovery(): String = when (this) {
    BrowserDocumentPreviewFailure.InvalidRequest -> "Check the argument limits."

    BrowserDocumentPreviewFailure.Busy ->
        "Another preview is rendering or too many web documents are open; retry later."

    BrowserDocumentPreviewFailure.RuntimeUnavailable ->
        "The browser component is not installed, is still being prepared or failed to prepare; ask the user to " +
            "open a web mini-app once to install it, then retry. Do not retry repeatedly."

    BrowserDocumentPreviewFailure.TimedOut -> "Simplify scripts that block loading, then retry once."

    BrowserDocumentPreviewFailure.ImageTooLarge -> "Use a smaller viewport or omit the screenshot."

    BrowserDocumentPreviewFailure.RenderFailed -> "Retry once; report the gap if it repeats."
}
