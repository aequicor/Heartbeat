package io.aequicor.heartbeat.platform.nativelauncher.bootstrap

import kotlinx.serialization.json.Json

private const val MAX_TCP_PORT = 65535

/** HTTPS обязателен; HTTP допускается только к loopback для локальных проверок provisioning. */
fun isAllowedArtifactUrl(url: String): Boolean {
    if (url.startsWith("https://")) return true
    if (!url.startsWith("http://")) return false
    val authority = url.removePrefix("http://").takeWhile { it != '/' && it != '?' && it != '#' }
    val match = Regex("(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::([0-9]+))?").matchEntire(authority) ?: return false
    val port = match.groupValues[2]
    return port.isEmpty() || port.toIntOrNull()?.let { it in 1..MAX_TCP_PORT } == true
}

/**
 * Разбор и валидация bootstrap-манифеста до любого использования его значений: лимит размера, поддерживаемая схема,
 * HTTPS-адреса, положительные размеры и строгий формат SHA-256. Подпись проверяется отдельно ([BootstrapSignature])
 * до вызова [parse]: доверенный манифест — это подписанные байты, а не только валидная структура.
 */
object BootstrapManifestVerifier {
    /** Верхняя граница манифеста: поля фиксированы схемой, большие документы отклоняются без разбора. */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("[0-9a-f]{64}")

    fun parse(bytes: ByteArray): BootstrapManifest {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "Bootstrap manifest is too large: ${bytes.size} bytes" }
        val manifest = json.decodeFromString<BootstrapManifest>(bytes.decodeToString())
        validate(manifest)
        return manifest
    }

    private fun validate(manifest: BootstrapManifest) {
        require(manifest.schema == BootstrapManifest.SCHEMA) { "Unsupported bootstrap schema: ${manifest.schema}" }
        require(manifest.version.isNotBlank()) { "Bootstrap version is blank" }
        require(manifest.channel.isNotBlank()) { "Bootstrap channel is blank" }
        require(manifest.os.isNotBlank() && manifest.arch.isNotBlank()) { "Bootstrap os/arch is blank" }
        require(manifest.minNativeLauncherVersion >= 1) { "Invalid minNativeLauncherVersion" }
        require(manifest.runtime.id.isNotBlank()) { "Runtime id is blank" }
        require(manifest.seed.trainId.isNotBlank()) { "Train id is blank" }
        listOf(manifest.runtime, manifest.platform, manifest.seed).forEach { artifact ->
            require(isAllowedArtifactUrl(artifact.url)) { "Artifact url must be HTTPS: ${artifact.url}" }
            require(artifact.size > 0) { "Artifact size must be positive: ${artifact.url}" }
            require(artifact.sha256.matches(sha256Regex)) { "Artifact sha256 is malformed: ${artifact.url}" }
        }
    }
}
