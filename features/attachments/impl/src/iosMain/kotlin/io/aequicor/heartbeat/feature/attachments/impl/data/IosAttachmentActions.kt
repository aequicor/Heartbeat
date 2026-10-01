package io.aequicor.heartbeat.feature.attachments.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentNativeActions
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

/** UIKit owns native documents; its host and delegate live only until the current UI operation completes. */
@OptIn(ExperimentalForeignApi::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class IosAttachmentActions(private val dispatchers: DispatcherProvider) : AttachmentNativeActions {
    private val log = Log.tag("NativeAttachments")

    override suspend fun choose(host: Any?, support: PromptInputSupport): List<AttachmentInput>? {
        log.i { "Choose attachment documents" }
        val picker = withContext(dispatchers.main) {
            UIDocumentPickerViewController(
                documentTypes = support.allowedMediaTypes.mapNotNull(::uniformType).distinct(),
                inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
            ).apply { allowsMultipleSelection = true }
        }
        val selected = presentPicker(host, picker) ?: return null
        if (selected.size > support.maxAttachments) throw AttachmentValidationException(AttachmentFailure.TooMany)
        return withContext(dispatchers.io) {
            var totalBytes = 0L
            selected.map { url ->
                val path = requireNotNull(url.path).toPath()
                val name = url.lastPathComponent ?: "attachment"
                val limit = minOf(support.maxFileBytes, MAX_BYTES)
                val bytes = readBounded(path.toString(), limit)
                totalBytes += bytes.size
                if (totalBytes > support.maxTotalBytes) throw AttachmentValidationException(AttachmentFailure.TooLarge)
                AttachmentInput.Bytes(name, attachmentMediaType(name), bytes)
            }
        }
    }

    private fun readBounded(path: String, maximum: Long): ByteArray =
        FileSystem.SYSTEM.source(path.toPath()).buffer().use { source ->
            source.request(maximum + 1)
            if (source.buffer.size > maximum) throw AttachmentValidationException(AttachmentFailure.TooLarge)
            source.readByteArray()
        }

    override suspend fun open(host: Any?, resource: ResolvedResource): Unit = withContext(dispatchers.main) {
        log.i { "Preview saved attachment using system document viewer" }
        val owner = requireNotNull(host as? UIViewController)
        val controller = UIDocumentInteractionController.interactionControllerWithURL(
            NSURL.fileURLWithPath(requireNotNull(resource.localPath)),
        )
        var delegate: UIDocumentInteractionControllerDelegateProtocol? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                delegate = object : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
                    override fun documentInteractionControllerViewControllerForPreview(
                        controller: UIDocumentInteractionController,
                    ): UIViewController = owner
                    override fun documentInteractionControllerDidEndPreview(
                        controller: UIDocumentInteractionController,
                    ) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                controller.delegate = delegate
                continuation.invokeOnCancellation { controller.dismissPreviewAnimated(false) }
                check(controller.presentPreviewAnimated(true)) { "No document preview available" }
            }
        } finally {
            controller.delegate = null
            delegate = null
        }
    }

    override suspend fun export(host: Any?, resource: ResolvedResource): Boolean {
        log.i { "Export saved attachment" }
        val directory = NSTemporaryDirectory().toPath() / "attachments-${Uuid.random()}"
        val path = directory / resource.name
        withContext(dispatchers.io) {
            FileSystem.SYSTEM.createDirectories(directory)
            FileSystem.SYSTEM.sink(path).buffer().use { it.write(resource.bytes) }
        }
        try {
            val picker = withContext(dispatchers.main) {
                UIDocumentPickerViewController(
                    forExportingURLs = listOf(NSURL.fileURLWithPath(path.toString())),
                    asCopy = true,
                )
            }
            return presentPicker(host, picker) != null
        } finally {
            withContext(
                NonCancellable + dispatchers.io,
            ) { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }
        }
    }

    private suspend fun presentPicker(host: Any?, picker: UIDocumentPickerViewController): List<NSURL>? = withContext(
        dispatchers.main,
    ) {
        val owner = requireNotNull(host as? UIViewController)
        var delegate: UIDocumentPickerDelegateProtocol? = null
        try {
            suspendCancellableCoroutine { continuation ->
                delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
                    override fun documentPicker(
                        controller: UIDocumentPickerViewController,
                        didPickDocumentsAtURLs: List<*>,
                    ) {
                        if (continuation.isActive) continuation.resume(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
                    }
                    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                picker.delegate = delegate
                continuation.invokeOnCancellation { picker.dismissViewControllerAnimated(false, null) }
                owner.presentViewController(picker, animated = true, completion = null)
            }
        } finally {
            picker.delegate = null
            delegate = null
        }
    }

    private fun uniformType(mime: String): String? = when (mime) {
        "image/jpeg" -> "public.jpeg"
        "image/png" -> "public.png"
        "image/gif" -> "com.compuserve.gif"
        "image/webp" -> "org.webmproject.webp"
        "text/plain", "text/markdown" -> "public.text"
        "application/pdf" -> "com.adobe.pdf"
        else -> null
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
