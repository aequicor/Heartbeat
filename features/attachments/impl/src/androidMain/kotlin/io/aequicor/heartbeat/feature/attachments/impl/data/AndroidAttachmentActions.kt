package io.aequicor.heartbeat.feature.attachments.impl.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

/** Application context owns IO; the Activity is only a local argument of a cancellable operation. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class AndroidAttachmentActions(private val context: Context, private val dispatchers: DispatcherProvider) :
    AttachmentNativeActions {
    private val log = Log.tag("NativeAttachments")

    override suspend fun choose(host: Any?, support: PromptInputSupport): List<AttachmentInput>? {
        log.i { "Choose attachment documents" }
        val selected = launch(
            host,
            ActivityResultContracts.OpenMultipleDocuments(),
            support.allowedMediaTypes.toTypedArray(),
        )
        if (selected.isEmpty()) return null
        if (selected.size > support.maxAttachments) throw AttachmentValidationException(AttachmentFailure.TooMany)
        return withContext(dispatchers.io) {
            var totalBytes = 0L
            selected.map { uri ->
                readSelected(uri, support).also {
                    totalBytes += it.bytes.size
                    if (totalBytes > support.maxTotalBytes) {
                        throw AttachmentValidationException(
                            AttachmentFailure.TooLarge,
                        )
                    }
                }
            }
        }
    }

    private fun readSelected(uri: Uri, support: PromptInputSupport): AttachmentInput.Bytes {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "attachment"
        val mime = context.contentResolver.getType(uri)?.takeIf(support::accepts) ?: attachmentMediaType(name)
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, support.maxFileBytes) }
            ?: throw AttachmentValidationException(AttachmentFailure.Missing)
        return AttachmentInput.Bytes(name, mime, bytes)
    }

    private fun readBounded(input: java.io.InputStream, maximum: Long): ByteArray {
        val limit = minOf(maximum, MAX_BYTES).toInt()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        if (output.size() > limit) throw AttachmentValidationException(AttachmentFailure.TooLarge)
        return output.toByteArray()
    }

    override suspend fun open(host: Any?, resource: ResolvedResource): Unit = withContext(dispatchers.main) {
        log.i { "Open saved attachment with system application" }
        val activity = activity(host)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.attachments.files",
            File(requireNotNull(resource.localPath)),
        )
        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW,
            ).setDataAndType(uri, resource.mediaType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    override suspend fun export(host: Any?, resource: ResolvedResource): Boolean {
        log.i { "Export saved attachment" }
        val target = launch(
            host,
            ActivityResultContracts.CreateDocument(resource.mediaType),
            resource.name,
        ) ?: return false
        withContext(dispatchers.io) {
            requireNotNull(context.contentResolver.openOutputStream(target, "wt")).use { it.write(resource.bytes) }
        }
        return true
    }

    private suspend fun <I, O> launch(host: Any?, contract: ActivityResultContract<I, O>, input: I): O = withContext(
        dispatchers.main,
    ) {
        val registry = (activity(host) as? ActivityResultRegistryOwner)?.activityResultRegistry
            ?: error("Attachment picker requires an ActivityResultRegistryOwner")
        var registered: ActivityResultLauncher<I>? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val launcher = registry.register("attachments:${Uuid.random()}", contract) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                registered = launcher
                continuation.invokeOnCancellation {
                    launcher.unregister()
                    log.d { "Native attachment request cancelled" }
                }
                launcher.launch(input)
            }
        } finally {
            registered?.unregister()
        }
    }

    private fun activity(host: Any?): Activity {
        var current = host as? Context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        return current as? Activity ?: error("Attachment operation requires a foreground Activity")
    }

    private companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
    }
}
