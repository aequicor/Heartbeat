package io.aequicor.heartbeat.feature.aistudio.screenmodel

/** Bounds of a page captured by the extension; native file URLs are never page attachments. */
fun NativeAttachmentUi.Page.isValid(): Boolean =
    (url.startsWith("https://") || url.startsWith("http://")) && url.length <= MAX_URL_CHARS &&
        title.length <= MAX_TITLE_CHARS && text.length <= MAX_PAGE_CHARS &&
        (selection?.length ?: 0) <= MAX_SELECTION_CHARS

/** Safe display name for the Markdown document; it is never a native path. */
fun NativeAttachmentUi.Page.fileName(): String {
    val base = title.ifBlank { url.substringAfter("://").substringBefore('/') }
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .trim().take(MAX_NAME_CHARS).ifBlank { "page" }
    return "$base.md"
}

/** The source URL, selected passage and readable page text passed to the model as one document. */
fun NativeAttachmentUi.Page.document(): String = buildString {
    append("# ").append(title.ifBlank { url }.lineSequence().first()).append("\n\n")
    append("Source: ").append(url).append("\n")
    selection?.takeIf { it.isNotBlank() }?.let {
        append("\n## Selection\n\n").append(it.trim()).append("\n")
    }
    if (text.isNotBlank()) append("\n## Page text\n\n").append(text.trim()).append("\n")
}

private const val MAX_NAME_CHARS = 80
private const val MAX_TITLE_CHARS = 512
private const val MAX_URL_CHARS = 4096
private const val MAX_PAGE_CHARS = 200_000
private const val MAX_SELECTION_CHARS = 20_000
