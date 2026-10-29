package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapArtifactInfo
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapManifest
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapManifestVerifier
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.BootstrapSignature
import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.sha256Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okio.FileSystem
import okio.Path
import okio.buffer

/** Этап provisioning для отображения в окне лончера. */
enum class ProvisionStage { MANIFEST, DOWNLOAD, EXTRACT, ACTIVATE }

data class ProvisionProgress(
    val stage: ProvisionStage,
    val artifact: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

/** Ожидаемые отказы provisioning; текст показывается пользователю в окне лончера. */
sealed class ProvisionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : ProvisionException(message, cause)
    class BadSignature : ProvisionException("Bootstrap manifest signature is not trusted")
    class InsufficientSpace(val requiredBytes: Long) :
        ProvisionException("Not enough free space for provisioning: $requiredBytes bytes required")
    class CorruptedDownload(url: String) : ProvisionException("Downloaded artifact failed size/SHA-256 check: $url")
    class LockTimeout : ProvisionException("Another instance is provisioning; timed out waiting for it")
}

/**
 * Полный цикл первого запуска и обновления: манифест → проверка подписи и схемы → загрузка runtime/platform/seed
 * с resume через Range → проверка размера и SHA-256 → безопасная распаковка в staging → атомарная активация.
 * Повторный запуск использует [currentOfflineImage] и не требует сети; удалённый манифест не блокирует offline-запуск.
 * Блокировка исключает параллельный provisioning: ожидающий экземпляр принимает готовый image владельца.
 * Отмена оставляет частичные `.part` для resume, удаляет staging и не активирует неполный image.
 */
class BootstrapProvisioner(
    private val store: ImageStore,
    private val transport: HttpTransport,
    private val manifestUrl: String,
    private val signatureUrl: String,
    private val trustedKeys: List<ByteArray>,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val freeSpace: (Path) -> Long = ProvisioningPlatform::freeSpaceBytes,
    private val lockPollMillis: Long = 500,
    private val lockTimeoutMillis: Long = DEFAULT_LOCK_TIMEOUT_MILLIS,
) {
    private val log = Log.tag("Provision")

    /** Проверенный локальный image для запуска без сети. */
    fun currentOfflineImage(): ProvisionedImage? = store.currentImage()

    suspend fun provision(
        onProgress: (ProvisionProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ProvisionedImage {
        val before = store.currentImage()
        val lock = acquireLock(isCancelled)
        try {
            return provisionLocked(before, onProgress, isCancelled)
        } finally {
            lock.release()
        }
    }

    private suspend fun provisionLocked(
        before: ProvisionedImage?,
        onProgress: (ProvisionProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ProvisionedImage {
        val current = store.currentImage()
        if (current != null && current.id != before?.id) {
            // Владелец блокировки уже собрал image, пока этот экземпляр ждал: дублировать работу не нужно.
            return current
        }
        val manifest = fetchManifest(isCancelled)
        val imageId = imageId(manifest)
        return if (current?.id == imageId) current else provisionNewImage(manifest, imageId, onProgress, isCancelled)
    }

    private suspend fun provisionNewImage(
        manifest: BootstrapManifest,
        imageId: String,
        onProgress: (ProvisionProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): ProvisionedImage {
        val totalBytes = manifest.runtime.size + manifest.platform.size + manifest.seed.size
        onProgress(ProvisionProgress(ProvisionStage.MANIFEST, totalBytes = totalBytes))
        val required = totalBytes * 2
        val free = freeSpace(store.root)
        if (free < required) throw ProvisionException.InsufficientSpace(required)
        val artifacts = listOf(
            Artifact("runtime", manifest.runtime),
            Artifact("platform", manifest.platform),
            Artifact("seed", manifest.seed),
        )
        val files = artifacts.map { artifact -> downloadArtifact(artifact, onProgress, isCancelled) }
        val staging = store.stagingDir(imageId)
        try {
            if (fs.exists(staging)) fs.deleteRecursively(staging)
            fs.createDirectories(staging)
            onProgress(ProvisionProgress(ProvisionStage.EXTRACT))
            val extractor = SafeZipExtractor(fs)
            extractor.extract(files[0], staging)
            checkCancelled(isCancelled)
            extractor.extract(files[1], staging)
            checkCancelled(isCancelled)
            extractor.extract(files[2], staging.resolve("app/resources/seed"))
            checkCancelled(isCancelled)
            val image = ProvisionedImage(
                imageId,
                store.imageDir(imageId),
                manifest.runtime.id,
                manifest.seed.trainId,
            )
            onProgress(ProvisionProgress(ProvisionStage.ACTIVATE))
            store.activate(image, staging)
            store.prune()
            return checkNotNull(store.currentImage()) { "Activated image is missing" }
        } catch (error: CancellationException) {
            cleanupStaging(staging)
            throw error
        }
    }

    private data class Artifact(val name: String, val info: BootstrapArtifactInfo)

    private fun imageId(manifest: BootstrapManifest): String {
        val raw = "${manifest.version}-${manifest.seed.trainId}-${manifest.platform.sha256.take(HASH_PREFIX)}"
        return raw.map { if (it.isLetterOrDigit() || it in SAFE_ID_PUNCTUATION) it else '-' }.joinToString("")
    }

    private suspend fun acquireLock(isCancelled: () -> Boolean): ProvisionLockHandle {
        var waited = 0L
        while (true) {
            ProvisioningPlatform.tryLock(store.lockFile)?.let { return it }
            if (waited >= lockTimeoutMillis) throw ProvisionException.LockTimeout()
            delay(lockPollMillis)
            waited += lockPollMillis
            if (isCancelled()) throw CancellationException("Provisioning cancelled while waiting for the lock")
        }
    }

    private suspend fun fetchManifest(isCancelled: () -> Boolean): BootstrapManifest {
        checkCancelled(isCancelled)
        require(manifestUrl.startsWith("https://") && signatureUrl.startsWith("https://")) {
            "Bootstrap manifest and signature must use HTTPS"
        }
        val manifestBytes = get(manifestUrl, BootstrapManifestVerifier.MAX_MANIFEST_BYTES.toLong())
        val signature = get(signatureUrl, BootstrapSignature.SIGNATURE_BYTES.toLong())
        checkCancelled(isCancelled)
        if (!BootstrapSignature.verify(manifestBytes, signature, trustedKeys)) {
            throw ProvisionException.BadSignature()
        }
        return BootstrapManifestVerifier.parse(manifestBytes)
    }

    private suspend fun get(url: String, limit: Long): ByteArray {
        val response = try {
            transport.getBytes(url, limit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ProvisionException.Network("Cannot download $url: $error", error)
        }
        if (response.status != HTTP_OK) networkError("HTTP ${response.status} for $url")
        if (response.body.size > limit) networkError("Response exceeds the limit for $url")
        return response.body
    }

    private suspend fun downloadArtifact(
        artifact: Artifact,
        onProgress: (ProvisionProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): Path {
        val file = store.downloadsDir.resolve("${artifact.name}.part")
        fs.createDirectories(store.downloadsDir)
        val info = artifact.info
        val partSize = if (fs.exists(file)) fs.metadata(file).size ?: 0L else 0L
        if (partSize > info.size) fs.delete(file)
        val offset = if (fs.exists(file)) fs.metadata(file).size ?: 0L else 0L
        var downloaded = offset
        var sink: okio.BufferedSink? = null
        val result = try {
            transport.download(
                info.url,
                offset,
                onStart = { isResumed ->
                    sink = if (isResumed && offset > 0) {
                        fs.appendingSink(file).buffer()
                    } else {
                        // Сервер не поддержал Range: пишем с нуля, а не дописываем к хвосту.
                        downloaded = 0
                        fs.sink(file).buffer()
                    }
                },
                onChunk = { chunk ->
                    checkCancelled(isCancelled)
                    checkNotNull(sink).write(chunk, 0, chunk.size)
                    downloaded += chunk.size
                    onProgress(ProvisionProgress(ProvisionStage.DOWNLOAD, artifact.name, downloaded, info.size))
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ProvisionException.Network("Cannot download ${info.url}: $error", error)
        } finally {
            sink?.close()
        }
        if (result.status !in SUCCESS_RANGE) networkError("HTTP ${result.status} for ${info.url}")
        return verifyDownload(file, info, downloaded)
    }

    private fun verifyDownload(file: Path, info: BootstrapArtifactInfo, downloaded: Long): Path {
        if (!fs.exists(file)) corrupted(info.url)
        val size = fs.metadata(file).size
        if (size != info.size || sha256Hex(fs.read(file) { readByteArray() }) != info.sha256) {
            fs.delete(file)
            corrupted(info.url)
        }
        log.i { "Artifact verified: $file ($downloaded bytes)" }
        return file
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Provisioning cancelled")
    }

    private fun cleanupStaging(staging: Path) {
        try {
            fs.deleteRecursively(staging)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Cannot remove cancelled staging: $staging" }
        }
    }

    private fun networkError(message: String, cause: Throwable? = null): Nothing =
        throw ProvisionException.Network(message, cause)

    private fun corrupted(url: String): Nothing = throw ProvisionException.CorruptedDownload(url)

    companion object {
        private const val HASH_PREFIX = 12
        private const val DEFAULT_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val HTTP_OK = 200
        private const val SAFE_ID_PUNCTUATION = "._-"
        private val SUCCESS_RANGE = HTTP_OK..299
    }
}
