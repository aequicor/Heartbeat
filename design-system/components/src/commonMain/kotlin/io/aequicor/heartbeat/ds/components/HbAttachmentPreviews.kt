package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.io.encoding.Base64

private val previewImage = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAACAAAAAUCAIAAABj86gYAAAAR0lEQVR4nGOwTltFU8Qw6Cz4fsKLmhYAjSMGkWMB" +
        "kUYTtAa7BWSYjsuOgbCAbNOx2jEsg4geqYia+UCuJ5mmaNSCUQuGgAUAOdBy8tm4SDEAAAAASUVORK5CYII=",
)

@Preview
@Composable
private fun AttachmentLightPreview() {
    HbTheme(darkTheme = false) { AttachmentPreviewContent() }
}

@Preview
@Composable
private fun AttachmentDarkPreview() {
    HbTheme(darkTheme = true) { AttachmentPreviewContent() }
}

@Composable
private fun AttachmentPreviewContent() {
    HbColumn {
        HbImage(previewImage, "Attached image")
        HbFlowRow {
            HbAttachmentThumbnail(previewImage, null, isImage = true)
            HbAttachmentThumbnail(null, "План исследования: сравнить подходы и собрать источники.", isImage = false)
            HbAttachmentThumbnail(null, null, isImage = true)
            HbAttachmentThumbnail(null, null, isImage = false)
            HbAttachmentThumbnail(null, null, isImage = true, isUnavailable = true)
        }
        HbRow {
            HbPasteImageButton("Paste image", onImage = {})
            HbPasteImageButton("Disabled", onImage = {}, enabled = false)
        }
        HbChatComposer(
            "", {}, {}, {}, "Send", "Stop", modifier = Modifier,
            hasAttachments = true, leadingContent = { HbText("Report.pdf · 16 KiB") },
        )
    }
}
