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

    /** Absolute overlay path, or null when no temporary file can be created; the engine then starts without images. */
    private val path: String? by lazy { create() }

    /** Arguments of `dsh`: the ACP profile plus the capability overlay when it is available. */
    fun arguments(): List<String> = dshAcpArguments(path)

    private fun create(): String? = try {
        Files.createTempFile("heartbeat-dsh-acp-", ".yml").toFile().apply {
            writeText(CONTENT)
            deleteOnExit()
        }.absolutePath
    } catch (error: IOException) {
        log.w(error) { "DeepSeek Harness ACP capability overlay could not be created" }
        null
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
