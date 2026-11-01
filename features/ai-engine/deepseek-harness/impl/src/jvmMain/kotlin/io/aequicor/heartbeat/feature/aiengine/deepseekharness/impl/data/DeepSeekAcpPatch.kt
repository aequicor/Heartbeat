package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data

import io.aequicor.heartbeat.core.logging.Log
import java.io.IOException
import java.nio.file.Files

/**
 * The shipped `acp` profile boots with `deepseek-official/deepseek-v4-flash`, whose catalog entry does not declare
 * image input. DSH therefore advertises `promptCapabilities.image = false` before any session can select a model,
 * and rejects every inline image for the lifetime of that connection. The overlay pins the profile's fallback route
 * to the official multimodal `deepseek-flash`; sessions still select their own model, and DSH re-validates image
 * input against that exact route before accepting an image. The user's profile on disk is never modified.
 */
internal object DeepSeekAcpPatch {
    /** Official DSH route whose built-in catalog declares `deepseek-flash` with image input. */
    internal const val PROVIDER: String = "deepseek-official"

    /** Multimodal model whose native catalog entry declares image input. */
    internal const val MODEL: String = "deepseek-flash"

    /** Overlay applied after the profile layers. */
    internal const val CONTENT: String = """- id: acp
  config:
    provider: $PROVIDER
    model: $MODEL
"""

    private val log = Log.tag("DeepSeekAcpPatch")

    /** Process-only overlay; failure prevents an unbound hosted transport from starting. */
    private val path: String by lazy { create() }

    /** Arguments of `dsh`: the ACP profile and required process-only capability/binding overlay. */
    fun arguments(): List<String> = dshAcpArguments(path)

    /** Insert is required for a new DSH plugin row; a bare id only patches an existing row. */
    internal fun hostedOverlay(pluginUri: String): String =
        CONTENT + "\n- insert:\n    - id: heartbeat-hosted-binding\n      name: '$pluginUri'\n"

    private fun create(): String {
        try {
            val plugin = Files.createTempFile("heartbeat-dsh-hosted-", ".mjs").toFile()
            plugin.deleteOnExit()
            val source = checkNotNull(DeepSeekAcpPatch::class.java.getResourceAsStream("/heartbeat-dsh-hosted.mjs")) {
                "DeepSeek hosted binding plugin is unavailable"
            }
            source.use { input -> plugin.outputStream().use(input::copyTo) }
            return Files.createTempFile("heartbeat-dsh-acp-", ".yml").toFile().apply {
                writeText(hostedOverlay(plugin.toURI().toString()))
                deleteOnExit()
            }.absolutePath
        } catch (error: IOException) {
            log.w(error) { "DeepSeek Harness ACP overlay could not be created" }
            throw IllegalStateException("DeepSeek Harness ACP overlay is unavailable", error)
        }
    }
}

/** Pure argument form so tests can cover the launcher contract without touching the file system. */
internal fun dshAcpArguments(patchPath: String?): List<String> = buildList {
    add("--profile")
    add("acp")
    patchPath?.let {
        add("--patch")
        add(it)
    }
}
