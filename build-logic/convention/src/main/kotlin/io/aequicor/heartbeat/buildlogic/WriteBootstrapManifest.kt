package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Пишет bootstrap-манифест тонкого установщика (схема v1) и его detached Ed25519-подпись: подписываются ровно
 * байты JSON. Размеры и SHA-256 вычисляются из собранных zip-артефактов (runtime/platform/seed), URL — из базового
 * адреса релиза и имён файлов. Ключ подписи — отдельный release-ключ (`heartbeat.bootstrapSigningKey`, PKCS#8
 * в base64), не ключ слотов. Без свойства локальная сборка подписывает одноразовым ключом и печатает его публичную
 * часть: такой манифест годится только для локальных проверок, релиз обязан передавать закреплённый ключ.
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

    @get:Input
    @get:Optional
    abstract val signingKey: Property<String>

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
        manifestFile.get().asFile.writeBytes(manifestBytes)
        val privateKey = signingKey()
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(manifestBytes)
        signatureFile.get().asFile.writeBytes(signer.sign())
        logger.lifecycle("Bootstrap manifest: {}", manifestFile.get().asFile)
    }

    private data class Artifact(val fileName: String, val url: String, val size: Long, val sha256: String)

    private fun artifact(file: RegularFileProperty, baseUrl: String): Artifact {
        val bytes = file.get().asFile.readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val name = file.get().asFile.name
        return Artifact(name, "$baseUrl/$name", bytes.size.toLong(), digest.joinToString("") { "%02x".format(it) })
    }

    private fun artifactFields(artifact: Artifact): String =
        "    \"url\": \"${escape(artifact.url)}\",\n" +
            "    \"size\": ${artifact.size},\n" +
            "    \"sha256\": \"${artifact.sha256}\"\n"

    private fun signingKey(): PrivateKey {
        val encoded = signingKey.orNull?.trim()
        val factory = KeyFactory.getInstance("Ed25519")
        if (!encoded.isNullOrEmpty()) {
            return factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        }
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        logger.warn(
            "heartbeat.bootstrapSigningKey is not set: the bootstrap manifest is signed with a one-off key " +
                "usable only for local checks. Public key (X.509, base64): {}",
            Base64.getEncoder().encodeToString(pair.public.encoded),
        )
        return pair.private
    }

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
