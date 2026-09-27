package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme

private const val PREVIEW_DIFF = "--- a/src/Studio.kt\n+++ b/src/Studio.kt\n@@ -1,3 +1,3 @@\n" +
    " HbTheme {\n-    PlainConversation()\n+    GlassConversation()\n }"

@Preview(name = "Diff · light", widthDp = 480, heightDp = 200)
@Composable
private fun DiffLightPreview() {
    HbTheme(darkTheme = false) { HbDiffView(PREVIEW_DIFF, Modifier.padding(HbTheme.spacing.m)) }
}

@Preview(name = "Diff · dark", widthDp = 480, heightDp = 200)
@Composable
private fun DiffDarkPreview() {
    HbTheme(darkTheme = true) { HbDiffView(PREVIEW_DIFF, Modifier.padding(HbTheme.spacing.m)) }
}
