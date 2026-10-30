package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Пишет bootstrap-манифест тонкого установщика (схема v1) и его detached Ed25519-подпись: подписываются ровно
 * байты JSON. Размеры и SHA-256 вычисляются из собранных zip-артефактов (runtime/platform/seed), URL — из базового
 * адреса релиза и имён файлов. Ключ подписи — отдельный release-ключ из HEARTBEAT_BOOTSTRAP_SIGNING_KEY (PKCS#8
 * Base64), не ключ слотов; подпись обязана проверяться встроенными native ключами. Без ключа сборка закрыта.
 * Одноразовая подпись разрешена только явно для локальных проверок (allowEphemeralKey), публичная часть выводится
 * в журнал. Секрет не является входом задачи: среди inputs хранится только SHA-256.
 */
abstract class WriteBootstrapManifest : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val channel: Property<String>

    @get:Input
    abstract val os: Property<String>

    @get:Input
    abstract val arch: Property<String>

    @get:Input
    abstract val runtimeId: Property<String>

    @get:Input
    abstract val trainId: Property<String>

    @get:Input
    abstract val releaseBaseUrl: Property<String>

    @get:Internal
    abstract val signingKey: Property<String>

    @get:Input
    @get:Optional
    val signingKeyDigest: Provider<String>
        get() = signingKey.map { it.filterNot(Char::isWhitespace) }.filter { it.isNotEmpty() }
            .map { sha256Hex(it.toByteArray()) }

    @get:Input
    abstract val allowEphemeralKey: Property<Boolean>

    @get:InputFile
    abstract val trustedKeysFile: RegularFileProperty

    init {
        allowEphemeralKey.convention(false)
    }

    @get:InputFile
    abstract val runtimeFile: RegularFileProperty

    @get:InputFile
    abstract val platformFile: RegularFileProperty

    @get:InputFile
    abstract val seedFile: RegularFileProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val signatureFile: RegularFileProperty

    @TaskAction
    fun write() {
        val baseUrl = releaseBaseUrl.get().trimEnd('/')
        val runtime = artifact(runtimeFile, baseUrl)
        val platform = artifact(platformFile, baseUrl)
        val seed = artifact(seedFile, baseUrl)
        val manifest = buildString {
            append("{\n")
            append("  \"schema\": 1,\n")
            append("  \"version\": \"${escape(version.get())}\",\n")
            append("  \"channel\": \"${escape(channel.get())}\",\n")
            append("  \"os\": \"${escape(os.get())}\",\n")
            append("  \"arch\": \"${escape(arch.get())}\",\n")
            append("  \"minNativeLauncherVersion\": 1,\n")
            append("  \"runtime\": {\n")
            append("    \"id\": \"${escape(runtimeId.get())}\",\n")
            append(artifactFields(runtime))
            append("  },\n")
            append("  \"platform\": {\n")
            append(artifactFields(platform))
            append("  },\n")
            append("  \"seed\": {\n")
            append("    \"trainId\": \"${escape(trainId.get())}\",\n")
            append(artifactFields(seed))
            append("  }\n")
            append("}\n")
        }
        val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
        val trustedKeys = trustedKeysFile.get().asFile.readLines()
            .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        val signed = signBootstrapManifest(manifestBytes, signingKey.orNull, trustedKeys, allowEphemeralKey.get())
        signed.ephemeralPublicKey?.let { publicKey ->
            logger.warn("Local-only bootstrap signature, one-off public key (X.509 Base64): {}", publicKey)
        }
        manifestFile.get().asFile.writeBytes(manifestBytes)
        signatureFile.get().asFile.writeBytes(signed.signature)
        logger.lifecycle("Bootstrap manifest: {}", manifestFile.get().asFile)
    }

    private data class Artifact(val fileName: String, val url: String, val size: Long, val sha256: String)

    private fun artifact(file: RegularFileProperty, baseUrl: String): Artifact {
        val source = file.get().asFile
        val name = source.name
        return Artifact(name, "$baseUrl/$name", source.length(), sha256Hex(source))
    }

    private fun artifactFields(artifact: Artifact): String =
        "    \"url\": \"${escape(artifact.url)}\",\n" +
            "    \"size\": ${artifact.size},\n" +
            "    \"sha256\": \"${artifact.sha256}\"\n"

    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
