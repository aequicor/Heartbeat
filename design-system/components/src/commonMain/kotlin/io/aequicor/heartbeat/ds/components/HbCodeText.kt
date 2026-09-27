package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.aequicor.heartbeat.ds.theme.HbTheme
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
                val color = when (span.kind) {
                    HbCodeTokenKind.Keyword -> colors.syntaxKeyword
                    HbCodeTokenKind.String -> colors.syntaxString
                    HbCodeTokenKind.Number -> colors.syntaxNumber
                    HbCodeTokenKind.Comment -> colors.syntaxComment
                    HbCodeTokenKind.Type -> colors.syntaxType
                    HbCodeTokenKind.Function -> colors.syntaxFunction
                    HbCodeTokenKind.Annotation -> colors.syntaxAnnotation
                }
                addStyle(SpanStyle(color = color), span.start, span.end)
            }
        }
    }
    BasicText(text = annotated, modifier = modifier, style = HbTheme.typography.code.copy(color = foreground))
}
