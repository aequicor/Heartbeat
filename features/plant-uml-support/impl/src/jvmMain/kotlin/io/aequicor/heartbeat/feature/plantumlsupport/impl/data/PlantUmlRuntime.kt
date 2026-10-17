package io.aequicor.heartbeat.feature.plantumlsupport.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

/** App-owned preparation survives a diagram leaving the viewport; failed attempts can be retried by a later render. */
internal class PlantUmlRuntime(
    private val cache: VerifiedArtifactCache,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val bundled: () -> Path? = ::bundledPlantUmlJar,
) {
    private val log = Log.tag("PlantUmlRuntime")
    private val mutex = Mutex()
    private var preparation: Deferred<Path>? = null

    @Volatile
    private var prepared: Path? = null

    /** Revalidates completed cache entries before rendering; concurrent callers share only the active preparation. */
    suspend fun prepare(): Path = mutex.withLock {
        preparation?.takeIf { it.isActive } ?: scope.async(io) {
            log.v { "Preparing the pinned PlantUML component" }
            val artifact = plantUmlArtifact()
            val path = verifiedBundled() ?: cache.prepare(artifact)
            prepared = path
            log.d { "Pinned PlantUML component is ready" }
            path
        }.also { preparation = it }
    }.await()

    /**
     * A repackaged development distribution rewrites the dependency jar, so a bundled file that does not match the
     * pin is not executed; the verified download takes its place.
     */
    private fun verifiedBundled(): Path? {
        val path = bundled() ?: return null
        return try {
            validatePlantUmlRuntimeJar(path)
            path
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Bundled PlantUML component does not match the pin; using the verified download" }
            null
        }
    }

    /** Read only by the blocking worker launcher after [prepare] has completed. */
    fun file(): Path = checkNotNull(prepared) { "PlantUML component has not been prepared" }
}

/** The application chooses an exact publisher artifact, never a latest release or a source-selected URL. */
internal fun plantUmlArtifact(): ReleaseArtifact {
    val properties = Properties()
    checkNotNull(PlantUmlRuntime::class.java.getResourceAsStream("/plantuml/runtime.properties")) {
        "PlantUML runtime lock is missing"
    }.use(properties::load)
    val version = properties.getProperty("version")
    val sha256 = properties.getProperty("sha256")
    val size = properties.getProperty("size")?.toLongOrNull()
    require(version != null && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid PlantUML version" }
    require(sha256 != null && sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid PlantUML checksum" }
    require(size != null && size in 1..MAX_RUNTIME_BYTES) { "Invalid PlantUML size" }
    val name = "plantuml-mit-$version.jar"
    return ReleaseArtifact(
        name,
        ReleaseDownload(
            "https://$MAVEN_HOST/maven2/net/sourceforge/plantuml/plantuml-mit/$version/$name",
            sha256,
            size,
            setOf(MAVEN_HOST),
            maxBytes = MAX_RUNTIME_BYTES,
        ),
    )
}

/** Development and JVM tests may use the same pinned dependency supplied by Gradle; releases omit it. */
internal fun bundledPlantUmlJar(): Path? {
    val resource = PlantUmlRuntime::class.java.classLoader.getResource(
        "net/sourceforge/plantuml/SourceStringReader.class",
    )
        ?: return null
    val connection = resource.openConnection() as? JarURLConnection ?: return null
    return Path.of(connection.jarFileURL.toURI()).takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
}

/** Rechecks the private worker's only external code input before a class loader can execute it. */
internal fun validatePlantUmlRuntimeJar(path: Path) {
    val download = plantUmlArtifact().download
    require(
        Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == download.size,
    ) { "Invalid PlantUML runtime file" }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        var count = input.read(buffer)
        while (count >= 0) {
            digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
    }
    require(HexFormat.of().formatHex(digest.digest()) == download.sha256) { "Invalid PlantUML runtime checksum" }
}

private const val MAVEN_HOST = "repo.maven.apache.org"
private const val MAX_RUNTIME_BYTES = 32L * 1024 * 1024
