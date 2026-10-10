package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.harness.api.script.HARNESS_API_VERSION
import io.aequicor.heartbeat.feature.harness.api.script.HarnessScriptBase
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import io.aequicor.heartbeat.feature.scheduler.api.WakeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/** URI conversion preserves escaped spaces and Windows drive letters in installed application paths. */
internal class HarnessScriptClasspath {
    val entries: List<Path> = listOf(
        HarnessScriptBase::class.java,
        Unit::class.java,
        CoroutineScope::class.java,
        JsonObject::class.java,
        KSerializer::class.java,
        SessionRef::class.java,
        WakeId::class.java,
    ).map(::location).distinct()

    private val fingerprint by lazy {
        (
            entries + listOf(
                location(ScriptCompilationConfiguration::class.java),
                location(BasicJvmScriptingHost::class.java),
                location(org.jetbrains.kotlin.cli.jvm.K2JVMCompiler::class.java),
            )
        ).distinct().map { it.contentFingerprint() }.joinToString("\n").encodeUtf8().sha256().hex()
    }

    fun key(request: HarnessCompilationRequest, appVersion: String): String = Json.encodeToString(
        listOf(request.source, request.kind.name, HARNESS_API_VERSION.toString(), fingerprint, appVersion),
    ).encodeUtf8().sha256().hex()

    private fun location(type: Class<*>): Path = Paths.get(
        checkNotNull(type.protectionDomain.codeSource).location.toURI(),
    )
}

private fun Path.contentFingerprint(): String {
    if (Files.isRegularFile(this)) return fileDigest()
    val digest = MessageDigest.getInstance("SHA-256")
    Files.walk(this).use { paths ->
        paths.filter(Files::isRegularFile).sorted().forEach { file ->
            digest.update(relativize(file).toString().toByteArray(Charsets.UTF_8))
            digest.update(file.fileDigest().toByteArray(Charsets.UTF_8))
        }
    }
    return digest.digest().toByteString().hex()
}
