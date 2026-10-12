package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import kotlinx.collections.immutable.ImmutableList

/** Draws prepared syntax ranges without changing text, selection or scroll ownership. */
@Composable
internal fun HbCodeText(
    text: String,
    spans: ImmutableList<HbCodeSpan>,
    modifier: Modifier = Modifier,
    foreground: Color = HbTheme.colors.textPrimary,
) {
    val colors = HbTheme.colors
    val annotated = remember(text, spans, colors) {
        buildAnnotatedString {
            append(text)
            spans.forEach { span ->
                addStyle(SpanStyle(color = colors.syntaxColor(span.kind)), span.start, span.end)
            }
        }
    }
    BasicText(text = annotated, modifier = modifier, style = HbTheme.typography.code.copy(color = foreground))
}

/** Theme colour of a syntax role, shared by read-only code and the code editor. */
internal fun HbColors.syntaxColor(kind: HbCodeTokenKind): Color = when (kind) {
    HbCodeTokenKind.Keyword -> syntaxKeyword
    HbCodeTokenKind.String -> syntaxString
    HbCodeTokenKind.Number -> syntaxNumber
    HbCodeTokenKind.Comment -> syntaxComment
    HbCodeTokenKind.Type -> syntaxType
    HbCodeTokenKind.Function -> syntaxFunction
    HbCodeTokenKind.Annotation -> syntaxAnnotation
}
