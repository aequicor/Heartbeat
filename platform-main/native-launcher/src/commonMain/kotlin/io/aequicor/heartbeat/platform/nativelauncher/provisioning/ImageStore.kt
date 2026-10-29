package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/** Полностью проверенный и активированный image приложения, готовый к запуску без сети. */
data class ProvisionedImage(val id: String, val directory: okio.Path, val runtimeId: String, val trainId: String)

/**
 * Раскладка provisioning в корне [root]:
 * `downloads/` — частичные файлы для resume, `staging/<id>` — неполный image (из него ничего не запускается),
 * `images/<id>` — проверенные image, `current`/`previous` — указатели на текущий и прежний рабочий image.
 * Активация атомарна: marker пишется в staging, затем каталог переименовывается в `images/<id>`, затем
 * атомарно заменяется `current`. Падение в любой точке не делает неполный image текущим, а прежний рабочий
 * image остаётся нетронутым до полной готовности нового.
 */
class ImageStore(val root: Path, private val fs: FileSystem = FileSystem.SYSTEM) {
    private val log = Log.tag("Provision")
    private val json = Json { ignoreUnknownKeys = true }

    val downloadsDir: Path get() = root.resolve("downloads")
    val lockFile: Path get() = root.resolve("provision.lock")
    internal val currentFile: Path get() = root.resolve("current")
    private val previousFile: Path get() = root.resolve("previous")

    fun imageDir(id: String): Path = root.resolve("images").resolve(id)

    fun stagingDir(id: String): Path = root.resolve("staging").resolve(id)

    /** Текущий проверенный image для запуска без сети; null, если его нет или marker не соответствует указателю. */
    fun currentImage(): ProvisionedImage? = readPointer(currentFile)?.let(::readImage)

    fun previousImageId(): String? = readPointer(previousFile)

    fun readImage(id: String): ProvisionedImage? {
        val dir = imageDir(id)
        val marker = markerFile(dir)
        if (!fs.exists(marker)) return null
        return runCatching {
            val record = json.decodeFromString<ImageMarker>(fs.read(marker) { readUtf8() })
            if (record.id != id) return null
            ProvisionedImage(record.id, dir, record.runtimeId, record.trainId)
        }.onFailure { log.w(it) { "Cannot read provisioned image marker: $marker" } }.getOrNull()
    }

    fun markerFile(imageDir: Path): Path = imageDir.resolve("bootstrap-image.json")

    fun writeMarker(dir: Path, image: ProvisionedImage) {
        val record = ImageMarker(image.id, image.runtimeId, image.trainId)
        fs.write(markerFile(dir)) { writeUtf8(json.encodeToString(ImageMarker.serializer(), record)) }
    }

    /**
     * Активирует собранный в staging image: marker в staging, атомарное переименование, затем замена `current`.
     * Прежний `current` сохраняется в `previous` и не удаляется.
     */
    fun activate(image: ProvisionedImage, stagedDir: Path) {
        val target = imageDir(image.id)
        writeMarker(stagedDir, image)
        if (fs.exists(target)) {
            // Повторная активация того же id после сбоя между rename и записью указателя.
            checkNotNull(readImage(image.id)) { "Incomplete image directory blocks activation: $target" }
            fs.deleteRecursively(stagedDir)
        } else {
            fs.createDirectories(target.parent ?: root)
            fs.atomicMove(stagedDir, target)
        }
        val previous = readPointer(currentFile)
        if (previous != null && previous != image.id) {
            fs.write(previousFile) { writeUtf8(previous) }
        }
        val tmp = root.resolve("current.tmp")
        fs.write(tmp) { writeUtf8(image.id) }
        fs.atomicMove(tmp, currentFile)
        check(currentImage()?.id == image.id) { "Activated image is not readable: ${image.id}" }
    }

    /** Удаляет image-каталоги, не являющиеся текущим или прежним рабочим; вызывается только после активации. */
    fun prune() {
        val keep = setOfNotNull(readPointer(currentFile), readPointer(previousFile))
        val images = root.resolve("images")
        if (!fs.exists(images)) return
        fs.list(images).forEach { dir ->
            if (dir.name !in keep) {
                runCatching { fs.deleteRecursively(dir) }
                    .onFailure { log.w(it) { "Cannot prune old image: $dir" } }
            }
        }
    }

    private fun readPointer(file: Path): String? {
        if (!fs.exists(file)) return null
        return runCatching { fs.read(file) { readUtf8().trim() } }
            .onFailure { log.w(it) { "Cannot read image pointer: $file" } }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}

@Serializable
internal data class ImageMarker(val id: String, val runtimeId: String, val trainId: String)
