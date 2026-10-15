package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import java.net.JarURLConnection
import java.nio.file.Path

/** The compiler is tied to this application's Kotlin and scripting ABI, never selected from remote latest metadata. */
internal object HarnessCompilerArtifacts {
    const val VERSION = "2.4.20"
    private val hosts = setOf("repo.maven.apache.org")
    val artifacts: List<ReleaseArtifact> = listOf(
        artifact(
            "kotlin-compiler-embeddable",
            "cf97161430683fb9af96dc6a7017ffae1fe8737b80d12eee11f2fe3e76f8ded8",
            58_593_245,
        ),
        artifact(
            "kotlin-scripting-compiler-embeddable",
            "6eb8ce1d73b89107804b3bdf295791f52c0394cbe18bcdfb90103aee955ea1c5",
            800_439,
        ),
        artifact(
            "kotlin-scripting-compiler-impl-embeddable",
            "d895b182c4c7bdbd688efb360a897f2182774d91e6c57a7acbeb2dc8f13f5ff2",
            199_451,
        ),
        artifact(
            "kotlin-daemon-embeddable",
            "eef72cf8f17e6d98d33e5b7c7e1cab31a1cf5fc34084dd5e563e7b1f3ef53435",
            393_409,
        ),
        artifact(
            "kotlin-build-tools-api",
            "47a622dce7231b1916334b69a00bc1094adf6577e6492f9f06b3d9c2450fe459",
            468_126,
        ),
        artifact(
            "kotlin-scripting-jvm-host",
            "c4a0faeabc022de4c3d55948336be2336958fbdcd8ae5cdab7e8d0c6a7f7c182",
            133_836,
        ),
    )
    val fingerprint: String = "compiler-protocol-1:" + artifacts.joinToString(":") {
        "${it.fileName}:${it.download.sha256}"
    }

    /** Development already verifies these dependencies through Gradle; inspecting resources does not initialize K2. */
    fun bundled(): List<Path>? {
        val loader = HarnessCompilerArtifacts::class.java.classLoader
        val paths = MARKERS.map { marker ->
            val connection = loader.getResource(marker)?.openConnection() as? JarURLConnection ?: return null
            Path.of(connection.jarFileURL.toURI())
        }
        return paths.distinct().takeIf { it.size == artifacts.size }
    }

    private fun artifact(name: String, sha256: String, size: Long): ReleaseArtifact {
        val file = "$name-$VERSION.jar"
        return ReleaseArtifact(
            file,
            ReleaseDownload(
                "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/$name/$VERSION/$file",
                sha256,
                size,
                hosts,
            ),
        )
    }
}

private val MARKERS = listOf(
    "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class",
    "org/jetbrains/kotlin/scripting/compiler/plugin/AbstractScriptEvaluationExtension.class",
    "org/jetbrains/kotlin/scripting/IdentifiersKt.class",
    "org/jetbrains/kotlin/daemon/KotlinCompileDaemon.class",
    "org/jetbrains/kotlin/buildtools/api/CompilationService.class",
    "kotlin/script/experimental/jvmhost/BasicJvmScriptingHost.class",
)
