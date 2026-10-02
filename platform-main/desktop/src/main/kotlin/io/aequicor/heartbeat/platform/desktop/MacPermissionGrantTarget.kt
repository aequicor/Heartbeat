package io.aequicor.heartbeat.platform.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.geometry.Offset
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.aequicor.heartbeat.core.logging.Log
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import javax.imageio.ImageReader

/** The application a macOS privacy permission is granted to, as the guide's draggable tile shows it. */
internal class GrantTarget(private val file: File, val name: String, val icon: ByteArray?) {
    private val log = Log.tag("GrantTarget")

    /** Starts dragging the application from the tile; [offset] keeps the tile under the pointer. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun dragData(offset: Offset): DragAndDropTransferData {
        log.i { "permission guide tile dragged" }
        return DragAndDropTransferData(
            DragAndDropTransferable(ApplicationTransferable(file)),
            supportedActions = listOf(DragAndDropTransferAction.Copy, DragAndDropTransferAction.Link),
            dragDecorationOffset = offset,
            onTransferCompleted = { action -> log.i { "permission guide tile dropped action=${action ?: "none"}" } },
        )
    }

    override fun toString(): String = "GrantTarget(isBundle=${file.name.endsWith(APP_SUFFIX)})"
}

/**
 * Finds the application that receives this process's privacy permissions. macOS attributes them to the
 * "responsible" process: an installed Heartbeat is its own, while a development launch is attributed to the IDE or
 * terminal that started it, so its bundle is the one to drag into System Settings. Must not run on the AWT event
 * thread: icon and name are read on the AppKit main thread, which may wait for the event thread.
 */
internal class MacGrantTargets(private val cocoa: MacWindowAccess) {
    private val log = Log.tag("MacGrantTargets")
    private val lookUpClass = NativeLibrary.getInstance("objc").getFunction("objc_getClass")

    /** The target, or `null` when the responsible executable is unknown. */
    fun resolve(): GrantTarget? {
        val pid = responsiblePid() ?: ProcessHandle.current().pid()
        val executable = ProcessHandle.of(pid).flatMap { it.info().command() }.orElse(null)?.let(::File)
        if (executable == null) {
            log.w { "responsible executable is unknown; the guide has no tile" }
            return null
        }
        val target = applicationBundle(executable) ?: executable
        val isSelf = pid == ProcessHandle.current().pid()
        log.i { "permission target resolved bundle=${target != executable} self=$isSelf" }
        return describe(target)
    }

    /** `responsibility_get_pid_responsible_for_pid` is private libSystem API; without it this process stands in. */
    private fun responsiblePid(): Long? = try {
        val lookup = NativeLibrary.getInstance(LIB_SYSTEM).getFunction(RESPONSIBLE_PID)
        lookup.invokeInt(arrayOf<Any>(ProcessHandle.current().pid().toInt())).takeIf { it > 0 }?.toLong()
    } catch (e: UnsatisfiedLinkError) {
        log.w(e) { "responsible process lookup is unavailable; using this process" }
        null
    }

    /** Finder's display name and icon; a failure keeps the file name and the generic icon. */
    private fun describe(target: File): GrantTarget {
        var name = target.name.removeSuffix(APP_SUFFIX)
        var tiff: ByteArray? = null
        try {
            // Autoreleased results are drained by the pool onMainThread wraps around this block.
            cocoa.onMainThread {
                val path = cocoa.pointer(objcClass("NSString"), "stringWithUTF8String:", target.path)
                val files = cocoa.pointer(objcClass("NSFileManager"), "defaultManager")
                name = cocoa.pointer(cocoa.pointer(files, "displayNameAtPath:", path), "UTF8String").getString(0, UTF8)
                val workspace = cocoa.pointer(objcClass("NSWorkspace"), "sharedWorkspace")
                val data = cocoa.pointer(cocoa.pointer(workspace, "iconForFile:", path), "TIFFRepresentation")
                tiff = cocoa.pointer(data, "bytes").getByteArray(0, cocoa.number(data, "length").toInt())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "application name and icon are unavailable" }
        }
        return GrantTarget(target, name, tiff?.let(::iconPng))
    }

    private fun objcClass(name: String): Pointer =
        checkNotNull(lookUpClass.invokePointer(arrayOf(name))) { "Cocoa class $name is missing" }

    /** Encodes the largest representation that still fits [ICON_MAX_PX], so the tile stays sharp on Retina. */
    private fun iconPng(tiff: ByteArray): ByteArray? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(tiff))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val image = reader.read(reader.bestIndex())
                ByteArrayOutputStream().use { output ->
                    ImageIO.write(image, "png", output)
                    output.toByteArray()
                }
            } finally {
                reader.dispose()
            }
        }
    } catch (e: IOException) {
        log.w(e) { "application icon could not be converted" }
        null
    }

    private fun ImageReader.bestIndex(): Int = (0 until getNumImages(true))
        .filter { getWidth(it) <= ICON_MAX_PX }
        .maxByOrNull(::getWidth) ?: 0

    private companion object {
        const val LIB_SYSTEM = "/usr/lib/libSystem.B.dylib"
        const val RESPONSIBLE_PID = "responsibility_get_pid_responsible_for_pid"
        const val ICON_MAX_PX = 128
        const val UTF8 = "UTF-8"
    }
}

/** The innermost `.app` bundle holding [executable], or `null` for a bare command-line tool. */
internal fun applicationBundle(executable: File): File? =
    generateSequence(executable.parentFile) { it.parentFile }.firstOrNull { it.name.endsWith(APP_SUFFIX) }

/**
 * Offers the application as a dragged file, the way Finder does: System Settings' privacy lists accept it as a
 * file list or as a file URL.
 */
internal class ApplicationTransferable(private val file: File) : Transferable {
    private val flavors = arrayOf(DataFlavor.javaFileListFlavor, URL_FLAVOR)

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors

    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        DataFlavor.javaFileListFlavor -> listOf(file)
        URL_FLAVOR -> file.toURI().toURL()
        else -> throw UnsupportedFlavorException(flavor)
    }

    private companion object {
        val URL_FLAVOR = DataFlavor("application/x-java-url;class=java.net.URL")
    }
}

private const val APP_SUFFIX = ".app"
