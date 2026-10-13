package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun PlatformDateField(
    value: String,
    onValueChange: (String) -> Unit,
    text: HbDateFieldText,
    modifier: Modifier,
    enabled: Boolean,
) = TextDateField(value, onValueChange, text, modifier, enabled)
