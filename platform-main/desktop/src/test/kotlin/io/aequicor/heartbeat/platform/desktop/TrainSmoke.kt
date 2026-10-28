package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.platform.dibundle.bundleHealth
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import io.aequicor.heartbeat.platform.dibundle.hasProfileHealth
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

private val log = Log.tag("TrainSmoke")

/**
 * Headless smoke of a processed bundle train (`verifyShrunkTrain`): starts the production graph over the train on the
 * class path, opens a profile so the profile graphs of the bundles are created, and requires every bundle named in
 * the arguments after the report and log files to be healthy at the app level and in the profile graph. The task runs
 * it with the jars of the train, not with the classes of this module. The findings go to the report file (`args[0]`),
 * the logs of the run to the log file (`args[1]`); exit code 0 when all are healthy, 1 otherwise.
 */
fun main(args: Array<String>) {
    val report = File(args[0])
    // The console logger writes to stdout and stderr: the run keeps them in a file of its own, also when it hangs.
    PrintStream(FileOutputStream(File(args[1])), true, Charsets.UTF_8).also {
        System.setOut(it)
        System.setErr(it)
    }
    Log.init(isDebug = true)
    val findings = try {
        smoke(args.drop(2).toSet())
    } catch (e: Throwable) {
        log.e(e) { "The train did not start" }
        listOf(Finding("the train did not start: ${e.stackTraceToString()}", isHealthy = false))
    }
    report.writeText(findings.joinToString("\n", postfix = "\n") { it.text })
    exitProcess(if (findings.all { it.isHealthy }) 0 else 1)
}

private class Finding(val text: String, val isHealthy: Boolean)

private fun smoke(expected: Set<String>): List<Finding> {
    require(expected.isNotEmpty()) { "no bundles to check" }
    val graph = createHeartbeatGraph(isDevelopment = true)
    var failure: Throwable? = null
    try {
        runBlocking { graph.profileSessions.open(ProfileId("train-smoke")) }
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        // A failing close must not hide the reason the profile did not open.
        runCatching { runBlocking { graph.profileSessions.close() } }.onFailure { closing ->
            failure?.addSuppressed(closing) ?: log.w(closing) { "The smoke profile did not close" }
        }
    }
    // Without it an empty answer could not be told from "every profile graph is healthy": the attachment of the
    // bundles to the profile may be missing from a processed train.
    check(graph.hasProfileHealth()) { "the profile graphs of the bundles were never opened" }
    val health = graph.bundleHealth().mapKeys { it.key.value }
    return expected.sorted().map { id ->
        when (val state = health[id]) {
            BundleHealth.Healthy -> Finding("bundle $id: healthy", isHealthy = true)

            null -> Finding("bundle $id was not started", isHealthy = false)

            is BundleHealth.Degraded -> Finding("bundle $id is degraded: missing ${state.missing}", isHealthy = false)

            is BundleHealth.Failed ->
                Finding("bundle $id failed: ${state.cause.stackTraceToString()}", isHealthy = false)
        }
    }
}
