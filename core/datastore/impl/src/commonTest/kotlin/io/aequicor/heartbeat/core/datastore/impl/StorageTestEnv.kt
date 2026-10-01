package io.aequicor.heartbeat.core.datastore.impl

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogLevel
import io.aequicor.heartbeat.core.logging.LogSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** 2026-01-01T10:00:00Z */
internal val START: Instant = Instant.fromEpochMilliseconds(1_767_261_600_000)

/**
 * Storage infrastructure on the virtual time of a [TestScope]: a temporary root, a clock that follows the scheduler,
 * UTC for daily expiry, dispatchers on the test scheduler, an app scope, captured logs.
 */
internal class StorageTestEnv(private val test: TestScope) {
    val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "hb-datastore-${Random.nextLong().toULong()}"
    val clock = object : Clock {
        override fun now(): Instant = START + test.testScheduler.currentTime.milliseconds
    }
    val retentionClock = RetentionClock(clock) { TimeZone.UTC }
    val dispatcher = StandardTestDispatcher(test.testScheduler)
    val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
    }
    val errors = mutableListOf<Throwable>()
    val logs = mutableListOf<String>()
    val layout = StorageLayout { root.toString() }
    var app = newScope("app")
        private set

    init {
        val sink = LogSink { level, tag, error, message -> logs += "$level $tag $message ${error ?: ""}" }
        Log.init(isDebug = true, isTrace = true, sinks = listOf(sink))
    }

    fun newScope(name: String, parent: TestScopeHandle? = null) = TestScopeHandle(name, dispatcher, parent?.job) {
        errors += it
    }.also { scope ->
        if (parent != null) {
            val link = parent.onClose(scope::close)
            scope.onClose(link::dispose)
        }
    }

    fun registry(roomBuilders: RoomBuilderFactory = RoomBuilderFactory { _, _ -> error("no Room in this test") }) =
        StoreRegistry(layout, retentionClock, dispatchers, roomBuilders, app)

    /** Ends the "process": closes the app scope and waits until its DataStores have released their files. */
    suspend fun restartProcess() {
        app.close()
        app.job.cancelAndJoin()
        app = newScope("app")
    }

    suspend fun dispose() {
        app.close()
        app.job.cancelAndJoin()
        FileSystem.SYSTEM.deleteRecursively(root)
        Log.init(isDebug = false)
    }

    fun logged(level: LogLevel, tag: String): List<String> = logs.filter { it.startsWith("$level $tag ") }
}

/** Minimal [ScopeHandle]: close cancels the job, then runs the actions in reverse order. */
internal class TestScopeHandle(
    override val name: String,
    dispatcher: CoroutineDispatcher,
    parent: Job?,
    onError: (Throwable) -> Unit,
) : ScopeHandle {
    val job = SupervisorJob(parent)
    private val actions = mutableListOf<() -> Unit>()

    override val coroutineScope = CoroutineScope(
        job + dispatcher +
            CoroutineExceptionHandler { _, e ->
                Log.tag("TestScopeHandle").e(e) { "uncaught failure in scope $name" }
                onError(e)
            },
    )
    override val savedState: ScopeSavedState get() = error("not used by storages")
    override var isClosed: Boolean = false
        private set

    override fun onClose(action: () -> Unit): DisposableHandle {
        if (isClosed) {
            action()
            return DisposableHandle {}
        }
        actions += action
        return DisposableHandle { actions -= action }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        job.cancel()
        actions.asReversed().toList().forEach { it() }
    }
}
