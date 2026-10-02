package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.RotatingFileLogSink
import java.io.IOException
import java.nio.file.Path

/** Installs persistent diagnostics before constructing the graph; an unavailable disk keeps console logging. */
internal fun initializeDesktopLogging(isDebug: Boolean, isTrace: Boolean): RotatingFileLogSink? {
    Log.init(isDebug = true, isTrace = isTrace)
    val sink = try {
        RotatingFileLogSink(
            desktopLogDirectory(
                System.getProperty("os.name"),
                System.getProperty("user.home"),
                System.getenv("LOCALAPPDATA"),
            ),
        )
    } catch (e: IOException) {
        Log.tag("Desktop").w(e) { "Persistent diagnostics unavailable; using console logging" }
        null
    } catch (e: SecurityException) {
        Log.tag("Desktop").w(e) { "Persistent diagnostics denied; using console logging" }
        null
    }
    Log.init(isDebug = isDebug || sink == null, isTrace = isTrace, sinks = listOfNotNull(sink))
    return sink
}

/** Per-user platform paths; parallel processes get independent rotation names in the sink. */
internal fun desktopLogDirectory(os: String, home: String, localAppData: String?): Path = when {
    os.startsWith("Mac", ignoreCase = true) -> Path.of(home, "Library", "Logs", "Heartbeat")
    !localAppData.isNullOrBlank() -> Path.of(localAppData, "Aequicor", "Heartbeat", "logs")
    else -> Path.of(home, "AppData", "Local", "Aequicor", "Heartbeat", "logs")
}
