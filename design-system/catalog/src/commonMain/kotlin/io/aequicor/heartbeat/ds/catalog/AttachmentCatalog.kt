package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbAttachmentThumbnail
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbImage
import io.aequicor.heartbeat.ds.components.HbPasteImageButton
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.hbAttachmentInput
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.io.encoding.Base64

private val sampleImage = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAACAAAAAUCAIAAABj86gYAAAAR0lEQVR4nGOwTltFU8Qw6Cz4fsKLmhYAjSMGkWMB" +
        "kUYTtAa7BWSYjsuOgbCAbNOx2jEsg4geqYia+UCuJ5mmaNSCUQuGgAUAOdBy8tm4SDEAAAAASUVORK5CYII=",
)

@Composable
internal fun AttachmentCatalog(onInput: () -> Unit, modifier: Modifier = Modifier) {
    val capture = hbAttachmentInput(true, onFiles = { onInput() }, onImage = { onInput() })
    HbCard(modifier.fillMaxWidth().then(capture).testTag("attachment-catalog")) {
        HbText(hbString(HbString.Attachments), style = HbTheme.typography.title)
        HbImage(sampleImage, hbString(HbString.Attachments))
        HbFlowRow {
            HbAttachmentThumbnail(sampleImage, null, isImage = true)
            HbAttachmentThumbnail(null, "План исследования: сравнить подходы и собрать источники.", isImage = false)
            HbAttachmentThumbnail(null, null, isImage = true)
            HbAttachmentThumbnail(null, null, isImage = false)
            HbAttachmentThumbnail(null, null, isImage = true, isUnavailable = true)
        }
        HbFlowRow {
            HbChip(
                "Очень длинное имя документа исследования.pdf · 24 KiB",
                onClick = onInput,
                icon = HbIcons.FileText,
                trailingIcon = null,
            )
            HbPasteImageButton(hbString(HbString.PasteImage), onImage = { onInput() })
            HbPasteImageButton(hbString(HbString.Disabled), onImage = {}, enabled = false)
        }
    }
}
