package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlFailure
import io.aequicor.heartbeat.feature.plantumlsupport.api.PlantUmlResult
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlDiagramType
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlLimits
import io.aequicor.heartbeat.feature.plantumlsupport.impl.domain.PlantUmlSource
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import net.sourceforge.plantuml.TitledDiagram
import net.sourceforge.plantuml.core.Diagram
import net.sourceforge.plantuml.dot.GraphvizRuntimeEnvironment
import net.sourceforge.plantuml.dot.GraphvizUtils
import net.sourceforge.plantuml.error.PSystemError
import net.sourceforge.plantuml.preproc.Defines
import net.sourceforge.plantuml.security.SecurityProfile
import net.sourceforge.plantuml.security.SecurityUtils
import java.awt.AWTError
import java.io.ByteArrayOutputStream

/**
 * PlantUML (MIT build) drawing in the current process — the worker process of [ProcessPlantUmlEngine], which bounds
 * its memory and time. The sources come from language models and are untrusted, so the engine runs only under
 * PlantUML's SANDBOX security profile: no file or URL includes, no environment variables.
 * PlantUML reads the profile once per process from a system property; [JvmPlantUmlEngine] sets it before the first
 * drawing and refuses to draw if another profile is already active. The layout is always Smetana (forced process-wide
 * and set by the preamble), and the Graphviz executable is pinned to a path that cannot exist, so a `dot` — installed,
 * named by `GRAPHVIZ_DOT` or bundled for Windows — is never launched, not even by service diagrams (`version`,
 * `testdot`) that query it while being parsed. Only diagram classes of [PlantUmlDiagramType]s are drawn; service and
 * easter-egg diagrams are unsupported. Images larger than [PlantUmlLimits] would be cropped by PlantUML; they are
 * reported as too large instead. Recursion errors and, without `-XX:+ExitOnOutOfMemoryError`, memory errors during a
 * drawing are caught.
 */
internal class JvmPlantUmlEngine : PlantUmlEngine {
    private val isSandboxed: Boolean by lazy(::enterSandbox)

    override fun render(source: PlantUmlSource, preamble: List<String>, limits: PlantUmlLimits): PlantUmlResult {
        if (!isSandboxed) return PlantUmlResult.Failed(PlantUmlFailure.Internal)
        GraphvizUtils.setLocalImageLimit(limits.maxSide)
        return try {
            draw(source, preamble, limits)
        } catch (e: LinkageError) {
            // For example a feature that needs a library the MIT build does not contain.
            log.w(e) { "PlantUML misses a component for type=${source.type}" }
            PlantUmlResult.Failed(PlantUmlFailure.Internal)
        } catch (e: StackOverflowError) {
            log.w(e) { "PlantUML recursion overflow type=${source.type}" }
            PlantUmlResult.Failed(PlantUmlFailure.TooLarge)
        } catch (e: OutOfMemoryError) {
            log.e(e) { "PlantUML ran out of memory type=${source.type}" }
            PlantUmlResult.Failed(PlantUmlFailure.TooLarge)
        } catch (e: AWTError) {
            log.e(e) { "PlantUML graphics failed type=${source.type}" }
            PlantUmlResult.Failed(PlantUmlFailure.Internal)
        } finally {
            GraphvizUtils.removeLocalLimitSize()
        }
    }

    private fun draw(source: PlantUmlSource, preamble: List<String>, limits: PlantUmlLimits): PlantUmlResult {
        val reader = SourceStringReader(Defines.createEmpty(), source.text, preamble)
        return when (val diagram = reader.blocks.firstOrNull()?.diagram) {
            null -> PlantUmlResult.SyntaxError(null, NO_DIAGRAM)

            is PSystemError -> syntaxError(diagram, source, preamble)

            else -> if (diagram.javaClass.packageName in DrawnDiagramPackages) {
                export(diagram, source, limits)
            } else {
                log.d { "PlantUML ${diagram.javaClass.simpleName} is not a drawn diagram type=${source.type}" }
                PlantUmlResult.Unsupported
            }
        }
    }

    private fun export(diagram: Diagram, source: PlantUmlSource, limits: PlantUmlLimits): PlantUmlResult {
        val output = ByteArrayOutputStream()
        val image = diagram.exportDiagram(output, 0, FileFormatOption(FileFormat.PNG, false))
        val png = output.toByteArray()
        val size = pngSize(png)
        val cause = image.rootCause
        return when {
            cause != null -> {
                log.w(cause) { "PlantUML failed while drawing type=${source.type}" }
                PlantUmlResult.Failed(PlantUmlFailure.Internal)
            }

            size == null || image.width <= 0 || image.height <= 0 -> {
                log.w { "PlantUML produced no readable PNG for type=${source.type}" }
                PlantUmlResult.Failed(PlantUmlFailure.Internal)
            }

            size.exceeds(limits, png.size) -> PlantUmlResult.Failed(PlantUmlFailure.TooLarge)

            else -> {
                val scale = size.first.toFloat() / image.width
                PlantUmlResult.Image(png, size.first / scale, size.second / scale, scale)
            }
        }
    }

    /** A failing preamble line is the host's fault, not the author's: its error carries no fence line. */
    private fun syntaxError(
        diagram: PSystemError,
        source: PlantUmlSource,
        preamble: List<String>,
    ): PlantUmlResult.SyntaxError {
        val error = diagram.firstError
        val line = error?.line
        val position = (line?.location ?: diagram.lineLocation)?.position
        val text = line?.string
        val isPreamble = position != null && source.isPreambleLine(position) && text != null && text in preamble
        if (isPreamble) log.w { "PlantUML rejected a generated theme line type=${source.type}" }
        return PlantUmlResult.SyntaxError(
            line = if (isPreamble || position == null) null else source.fenceLine(position),
            message = error?.error?.trim().orEmpty().ifEmpty { SYNTAX_ERROR },
        )
    }

    private fun enterSandbox(): Boolean {
        System.setProperty(SECURITY_PROFILE_PROPERTY, SecurityProfile.SANDBOX.name)
        // No pragma or skin parameter of a diagram may switch the layout to an external Graphviz process.
        TitledDiagram.FORCE_SMETANA = true
        // Takes precedence over GRAPHVIZ_DOT and the bundled Windows dot. A path with an inner NUL never exists and
        // never starts a process; PlantUML trims the value, so the NUL must not be at either end.
        GraphvizRuntimeEnvironment.getInstance().setDotExecutable(NO_GRAPHVIZ)
        val profile = SecurityUtils.getSecurityProfile()
        if (profile != SecurityProfile.SANDBOX) {
            log.e { "PlantUML security profile is $profile instead of SANDBOX; diagrams are not drawn" }
        }
        return profile == SecurityProfile.SANDBOX
    }

    private companion object {
        val log = Log.tag("PlantUmlEngine")
        const val SECURITY_PROFILE_PROPERTY = "PLANTUML_SECURITY_PROFILE"
        const val NO_DIAGRAM = "No diagram found"
        const val SYNTAX_ERROR = "Syntax error"
        const val NO_GRAPHVIZ = "graphviz\u0000disabled"

        /** Packages of the diagram classes of [PlantUmlDiagramType]; anything else PlantUML builds is not drawn. */
        val DrawnDiagramPackages = setOf(
            "activitydiagram",
            "activitydiagram3",
            "cheneer",
            "classdiagram",
            "descdiagram",
            "ebnf",
            "gantt",
            "jsondiagram",
            "mindmap",
            "regexdiagram",
            "salt",
            "sequencediagram",
            "statediagram",
            "timingdiagram",
            "wbs",
        ).mapTo(HashSet()) { "net.sourceforge.plantuml.$it" }
    }
}

/** PlantUML crops at [PlantUmlLimits.maxSide] silently: an image that reaches it is treated as too large. */
private fun Pair<Int, Int>.exceeds(limits: PlantUmlLimits, bytes: Int): Boolean {
    val (width, height) = this
    val isCropped = width >= limits.maxSide || height >= limits.maxSide
    return isCropped || width.toLong() * height > limits.maxPixels || bytes > limits.maxPngBytes
}

/** Width and height from a PNG's IHDR chunk, or null for bytes that are not a PNG. */
internal fun pngSize(png: ByteArray): Pair<Int, Int>? {
    if (png.size < PNG_HEADER_BYTES || !PNG_SIGNATURE.indices.all { png[it] == PNG_SIGNATURE[it] }) return null
    return png.int(PNG_WIDTH_OFFSET) to png.int(PNG_WIDTH_OFFSET + Int.SIZE_BYTES)
}

private fun ByteArray.int(offset: Int): Int = (0 until Int.SIZE_BYTES).fold(0) { value, index ->
    (value shl Byte.SIZE_BITS) or (this[offset + index].toInt() and BYTE_MASK)
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
private const val PNG_WIDTH_OFFSET = 16
private const val PNG_HEADER_BYTES = 24
private const val BYTE_MASK = 0xFF
