package io.aequicor.heartbeat.feature.harness.impl.data.script.worker

import io.aequicor.heartbeat.feature.harness.impl.data.script.HarnessCompilerArtifacts
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Runs only in the private headless worker process. The app keeps its small worker classes; their compiler
 * dependencies are loaded from the pinned files passed by the parent, without adding them to the app class loader.
 * Revalidating the files here prevents a corrupt or replaced cache file from being loaded between processes.
 */
public fun main(arguments: Array<String>) {
    if (arguments.isEmpty()) {
        invokeCompiler(HarnessCompilerArtifacts::class.java.classLoader)
        return
    }
    val files = verifiedCompilerFiles(arguments.toList())
    val ownCode = checkNotNull(HarnessCompilerArtifacts::class.java.protectionDomain.codeSource).location
    val parent = HarnessCompilerArtifacts::class.java.classLoader
    CompilerClassLoader((listOf(ownCode) + files.map { it.toUri().toURL() }).toTypedArray(), parent).use { loader ->
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = loader
            invokeCompiler(loader)
        } finally {
            thread.contextClassLoader = previous
        }
    }
}

/** The path list is positional and complete: it cannot add unpinned executable code to the worker. */
internal fun verifiedCompilerFiles(arguments: List<String>): List<Path> {
    require(arguments.size == HarnessCompilerArtifacts.artifacts.size) { "Invalid compiler runtime file count" }
    return arguments.zip(HarnessCompilerArtifacts.artifacts).map { (name, artifact) ->
        val path = Path.of(name)
        require(path.isAbsolute && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Compiler runtime file is missing" }
        require(path.fileName.toString() == artifact.fileName) { "Compiler runtime file differs" }
        require(Files.size(path) == artifact.download.size) { "Compiler runtime size differs" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var count = input.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        require(HexFormat.of().formatHex(digest.digest()) == artifact.download.sha256) {
            "Compiler runtime checksum differs"
        }
        path
    }
}

private fun invokeCompiler(loader: ClassLoader) {
    Class.forName(WORKER_MAIN, true, loader).getMethod("main", Array<String>::class.java)
        .invoke(null, emptyArray<String>())
}

/** Worker classes must be defined by the loader that can resolve K2, even if they also occur in the parent's jar. */
private class CompilerClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val isChild = CHILD_PACKAGES.any(name::startsWith)
        val type = findLoadedClass(name) ?: if (isChild) findClass(name) else super.loadClass(name, false)
        if (resolve) resolveClass(type)
        type
    }
}

private val CHILD_PACKAGES = listOf(
    "io.aequicor.heartbeat.feature.harness.impl.data.script.",
    "org.jetbrains.kotlin.",
    "org.jetbrains.org.objectweb.asm.",
    "kotlin.script.experimental.jvmhost.",
)
private const val WORKER_MAIN = "io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerWorkerKt"
private const val COPY_BUFFER_BYTES = 64 * 1024
