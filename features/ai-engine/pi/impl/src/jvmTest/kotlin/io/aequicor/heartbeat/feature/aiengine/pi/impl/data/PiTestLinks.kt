package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import org.junit.AssumptionViolatedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/** Windows junctions exercise directory traversal without requiring symlink privileges. */
internal fun piTestDirectoryLink(link: Path, target: Path): Path {
    if (!isWindowsLinks) return Files.createSymbolicLink(link, target)
    val process = ProcessBuilder("cmd", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "Cannot create test junction: $output" }
    return link
}

/** A file symlink has no junction equivalent; only the Windows privilege error skips this exact scenario. */
internal fun piTestSymbolicLink(link: Path, target: Path): Path = try {
    Files.createSymbolicLink(link, target)
} catch (error: FileSystemException) {
    val reason = error.reason.orEmpty()
    val missingPrivilege = reason.contains("required privilege", ignoreCase = true) ||
        reason.contains("требуемыми правами", ignoreCase = true)
    if (!isWindowsLinks || !missingPrivilege) throw error
    throw AssumptionViolatedException("Windows file symlinks require Developer Mode or symlink privilege", error)
}

private val isWindowsLinks = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
