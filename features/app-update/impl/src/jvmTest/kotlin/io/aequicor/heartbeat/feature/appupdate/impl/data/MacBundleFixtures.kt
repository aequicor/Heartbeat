package io.aequicor.heartbeat.feature.appupdate.impl.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals

internal val isMacHost: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

/** A minimal app bundle `<directory>/<name>` of [version], shaped like the jpackage bundle. */
internal fun fakeBundle(directory: Path, version: String, name: String = "io.aequicor.app"): Path {
    val app = directory.resolve(name)
    Files.createDirectories(app.resolve("Contents/MacOS"))
    app.resolve("Contents/Info.plist").writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0"><dict>
          <key>CFBundleShortVersionString</key>
          <string>$version</string>
        </dict></plist>
        """.trimIndent(),
    )
    app.resolve("Contents/MacOS/io.aequicor").writeText("#!/bin/sh\n")
    return app
}

/** The version a bundle states in its `Info.plist`. */
internal fun bundleVersion(app: Path): String =
    Regex("<string>([^<]*)</string>").find(app.resolve("Contents/Info.plist").readText())?.groupValues?.get(1).orEmpty()

/** A compressed disk image of [source], like the release DMG (macOS only). */
internal fun diskImage(source: Path, image: Path) {
    Files.createSymbolicLink(source.resolve("Applications"), Path.of("/Applications"))
    runTool(
        "/usr/bin/hdiutil", "create", "-quiet", "-ov", "-srcfolder", "$source",
        "-volname", "Heartbeat", "-format", "UDZO", "$image",
    )
}

/** The id of a process that has already ended. */
internal fun endedProcessId(): Long {
    val process = ProcessBuilder("/usr/bin/true").start()
    process.waitFor()
    return process.pid()
}

/**
 * Runs the helper [command] with a stub `open` first on PATH that records what it would open in [opened],
 * so no app is actually started.
 */
internal fun runHelper(command: List<String>, stubs: Path, opened: Path): Int {
    Files.createDirectories(stubs)
    val open = stubs.resolve("open")
    open.writeText("#!/bin/sh\nprintf '%s\\n' \"$*\" >> '$opened'\n")
    open.toFile().setExecutable(true)
    val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
    builder.environment()["PATH"] = "$stubs:/usr/bin:/bin"
    val process = builder.start()
    check(process.waitFor(1, TimeUnit.MINUTES)) { "the helper did not finish" }
    return process.exitValue()
}

private fun runTool(vararg command: String) {
    val process = ProcessBuilder(*command).inheritIO().start()
    assertEquals(0, process.waitFor(), command.first())
}
