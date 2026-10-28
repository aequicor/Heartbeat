package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.RotatingFileLogSink
import io.aequicor.heartbeat.core.logging.desktopLegacyLogDirectories
import io.aequicor.heartbeat.core.logging.desktopLogDirectory
import java.io.IOException

/** Installs persistent diagnostics before constructing the graph; an unavailable disk keeps console logging. */
internal fun initializeDesktopLogging(isDebug: Boolean, isTrace: Boolean): RotatingFileLogSink? {
    Log.init(isDebug = true, isTrace = isTrace)
    val sink = try {
        RotatingFileLogSink(
            directory = desktopLogDirectory(),
            legacyDirectories = desktopLegacyLogDirectories(),
        )
    } catch (e: IOException) {
        Log.tag("Desktop").w(e) { "Persistent diagnostics unavailable; using console logging" }
        null
    } catch (e: SecurityException) {
        Log.tag("Desktop").w(e) { "Persistent diagnostics denied; using console logging" }
        null
    }
    Log.init(isDebug = isDebug || sink == null, isTrace = isTrace, sinks = listOfNotNull(sink))
    Log.tag("Desktop").i { trainLogLine(System.getProperty(TRAIN_PROPERTY), System.getProperty(VERSION_PROPERTY)) }
    return sink
}

/**
 * The line that names the bundle train of this launch in every log: `scripts/retrace.py` reads `train=` to pick the
 * mappings of the obfuscated names in stack traces. A launch without the platform launcher states no train.
 */
internal fun trainLogLine(train: String?, version: String?): String = "Launch: train=${train?.takeIf {
    it.isNotBlank()
} ?: "none"} version=${version?.takeIf { it.isNotBlank() } ?: "dev"}"

private const val TRAIN_PROPERTY = "heartbeat.train"
private const val VERSION_PROPERTY = "heartbeat.app.version"
