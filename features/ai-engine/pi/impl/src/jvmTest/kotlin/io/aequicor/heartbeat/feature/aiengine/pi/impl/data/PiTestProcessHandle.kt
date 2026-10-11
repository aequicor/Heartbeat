package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream

/** OS signal and exit are independent; never looks up or signals a real PID. */
internal class PiTestProcessHandle(
    private val alive: () -> Boolean,
    private val requestStop: () -> Unit,
    private val exited: CompletableFuture<Process>,
) : ProcessHandle {
    override fun pid(): Long = 123
    override fun parent(): Optional<ProcessHandle> = Optional.empty()
    override fun children(): Stream<ProcessHandle> = Stream.empty()
    override fun descendants(): Stream<ProcessHandle> = Stream.empty()
    override fun info(): ProcessHandle.Info = error("Unused")
    override fun onExit(): CompletableFuture<ProcessHandle> = exited.thenApply { this }
    override fun supportsNormalTermination(): Boolean = true
    override fun isAlive(): Boolean = alive()
    override fun destroy(): Boolean {
        requestStop()
        return true
    }
    override fun destroyForcibly(): Boolean = destroy()
    override fun compareTo(other: ProcessHandle): Int = pid().compareTo(other.pid())
}
