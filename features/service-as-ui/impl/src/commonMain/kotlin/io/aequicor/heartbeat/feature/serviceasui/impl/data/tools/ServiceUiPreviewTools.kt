package io.aequicor.heartbeat.feature.serviceasui.impl.data.tools

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolImage
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviewResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentPreviews
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiTools
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiWeb
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
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
) : AgentToolContribution {
    private val isDesktop = platform.host in setOf(HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.Linux)
    override val group: String = ServiceUiTools.GROUP
    override val title: String = "Интерфейсы"
    override val isDetachedSupported: Boolean = true
    override val catalog get() = listOf(ServiceUiPreviewSpec).toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (isEnabled()) listOf(ServiceUiPreviewSpec) else emptyList()

    override suspend fun instructions(workspace: WorkspaceRef?): String = if (isEnabled()) INSTRUCTIONS else ""

    override suspend fun instructions(scope: AgentToolScope): String {
        val declared = scope.declared
        return if (declared == null || ServiceUiTools.PREVIEW in declared) instructions(scope.workspace) else ""
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        if (name != ServiceUiTools.PREVIEW) return failure("Unknown preview tool")
        if (!isEnabled()) return failure("WebView preview requires Desktop and enabled interface web documents")
        val input = when (val parsed = previewArguments(arguments)) {
            is UiPreviewArguments.Valid -> parsed
            is UiPreviewArguments.Invalid -> return failure(parsed.message)
        }
        return when (val result = previews.preview(input.request)) {
            is BrowserDocumentPreviewResult.Rendered -> rendered(result, input)
            BrowserDocumentPreviewResult.Unavailable -> failure("WebView preview is unavailable on this host")
            is BrowserDocumentPreviewResult.Failure -> failure("WebView preview failed: ${result.reason.name}")
        }
    }

    private suspend fun isEnabled(): Boolean = isDesktop && toggles.get(ServiceUiEnabled) && toggles.get(ServiceUiWeb)

    private fun rendered(
        result: BrowserDocumentPreviewResult.Rendered,
        input: UiPreviewArguments.Valid,
    ): AgentToolResult {
        val isScreenshotIncluded = input.isScreenshotIncluded
        val report = buildJsonObject {
            put("scope", "isolated WebView draft; owner bridge disabled; no saved interface changed")
            put("theme", if (input.request.isDark) "dark" else "light")
            put("fontScale", input.request.fontScale)
            putJsonObject("viewport") {
                put("width", result.metrics.viewportWidth)
                put("height", result.metrics.viewportHeight)
            }
            putJsonObject("content") {
                put("width", result.metrics.contentWidth)
                put("height", result.metrics.contentHeight)
                put("horizontalOverflow", result.metrics.horizontalOverflow)
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
                result.samples.forEach { sample ->
                    add(
                        buildJsonObject {
                            put("element", sample.element)
                            put("tag", sample.tag)
                            put("x", sample.x)
                            put("y", sample.y)
                            put("width", sample.width)
                            put("height", sample.height)
                            put("fontFamily", sample.fontFamily)
                            put("fontSize", sample.fontSize)
                            put("lineHeight", sample.lineHeight)
                            put("color", sample.color)
                            put("backgroundColor", sample.backgroundColor)
                        },
                    )
                }
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
            "It uses the isolated host renderer without owner methods " +
            "or persistence. Default text diagnostics work with text-only models and do not verify aesthetic " +
            "quality. Set includeScreenshot=true only for models supporting image tool results, then inspect " +
            "the image. Supply realistic sample data, check narrow/wide and light/dark variants, fix defects and " +
            "preview again. This does not render native JSON or test the saved app's live owner flow."
    }
}

private fun failure(message: String): AgentToolResult = AgentToolResult(message, isError = true)
