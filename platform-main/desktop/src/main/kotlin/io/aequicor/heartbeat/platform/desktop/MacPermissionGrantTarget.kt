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
import javax.imageio.ImageIO

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
        permissionGuideOrNull("load the application name and icon") {
            // Autoreleased results are drained by the pool onMainThread wraps around this block.
            cocoa.onMainThread {
                val path = cocoa.pointer(objcClass("NSString"), "stringWithUTF8String:", target.path)
                val files = cocoa.pointer(objcClass("NSFileManager"), "defaultManager")
                name = cocoa.pointer(cocoa.pointer(files, "displayNameAtPath:", path), "UTF8String").getString(0, UTF8)
                val workspace = cocoa.pointer(objcClass("NSWorkspace"), "sharedWorkspace")
                tiff = boundedIcon(cocoa.pointer(workspace, "iconForFile:", path))
            }
        }
        return GrantTarget(target, name, tiff?.let(::permissionGuideIconPng))
    }

    private fun objcClass(name: String): Pointer =
        checkNotNull(lookUpClass.invokePointer(arrayOf(name))) { "Cocoa class $name is missing" }

    /**
     * Exports only an existing bitmap that fits the tile. NSImage.TIFFRepresentation serializes every image
     * representation (tens of MB for some app icons) on AppKit's main thread. Icons without a bounded bitmap use
     * the generic tile instead of allocating a full-size raster or invoking an unsupported TIFF selector.
     */
    private fun boundedIcon(image: Pointer): ByteArray? {
        val bitmapClass = objcClass("NSBitmapImageRep")
        val representations = cocoa.objects(cocoa.pointer(image, "representations"))
            .filter { cocoa.boolean(it, "isKindOfClass:", bitmapClass) }
        val sizes = representations.map { representation ->
            cocoa.number(representation, "pixelsWide") to cocoa.number(representation, "pixelsHigh")
        }
        val selected = permissionGuideIconIndex(sizes) ?: return null
        val data = cocoa.pointer(representations[selected], "TIFFRepresentation")
        val length = cocoa.number(data, "length")
        check(length in 1..ICON_MAX_BYTES) { "Application icon exceeds the encoded size limit" }
        return cocoa.pointer(data, "bytes").getByteArray(0, length.toInt())
    }

    private companion object {
        const val LIB_SYSTEM = "/usr/lib/libSystem.B.dylib"
        const val RESPONSIBLE_PID = "responsibility_get_pid_responsible_for_pid"
        const val UTF8 = "UTF-8"
    }
}

/** Selects a bounded native bitmap before encoding; invalid or oversized representations are never exported. */
internal fun permissionGuideIconIndex(sizes: List<Pair<Long, Long>>): Int? = sizes.indices
    .filter { sizes[it].first in 1..ICON_MAX_PX && sizes[it].second in 1..ICON_MAX_PX }
    .maxByOrNull { sizes[it].first * sizes[it].second }

/** Converts one bounded bitmap off the AppKit thread; codec and native failures keep the generic tile. */
internal fun permissionGuideIconPng(tiff: ByteArray): ByteArray? =
    permissionGuideOrNull("convert the application icon") {
        check(tiff.size <= ICON_MAX_BYTES) { "Application icon exceeds the encoded size limit" }
        ImageIO.createImageInputStream(ByteArrayInputStream(tiff))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = input
                check(reader.getWidth(0) in 1..ICON_MAX_PX && reader.getHeight(0) in 1..ICON_MAX_PX) {
                    "Application icon exceeds the pixel size limit"
                }
                val image = reader.read(0)
                ByteArrayOutputStream().use { output ->
                    check(ImageIO.write(image, "png", output)) { "No PNG encoder is available" }
                    output.toByteArray()
                }
            } finally {
                reader.dispose()
            }
        }
    }

private const val ICON_MAX_PX = 128L
private const val ICON_MAX_BYTES = 1_048_576L

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
