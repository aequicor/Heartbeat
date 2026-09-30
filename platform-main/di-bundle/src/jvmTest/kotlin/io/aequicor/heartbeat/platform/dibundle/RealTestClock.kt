package io.aequicor.heartbeat.platform.dibundle

import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Keeps storage observation deadlines on physical time; each test owns and closes its timer. */
internal class RealTestClock : AutoCloseable {
    val dispatcher = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "heartbeat-test-clock").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    override fun close() = dispatcher.close()
}
