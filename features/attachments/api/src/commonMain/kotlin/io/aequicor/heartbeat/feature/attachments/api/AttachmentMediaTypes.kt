package io.aequicor.heartbeat.feature.attachments.api

/**
 * Media type of a file [name] for the formats the durable importer accepts, or null when the extension is
 * unknown. UI uses this hint to mark files the current model cannot take before an import is attempted; the
 * importer remains the authority — its own sniffed type decides what is stored.
 */
public fun attachmentMediaTypeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "log", "kt", "kts", "java", "py", "js", "ts", "json", "xml", "yml", "yaml", "toml", "csv",
    "gradle", "properties", "sql", "sh", "ps1", "bat", "cmd", "c", "h", "cpp", "hpp", "cs", "go", "rs",
    "rb", "php", "swift", "html", "css",
    -> "text/plain"

    "md", "markdown" -> "text/markdown"

    "pdf" -> "application/pdf"

    "png" -> "image/png"

    "jpg", "jpeg" -> "image/jpeg"

    "gif" -> "image/gif"

    "webp" -> "image/webp"

    else -> null
}
