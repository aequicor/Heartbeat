package io.aequicor.heartbeat.platform.desktop

import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.platform.dibundle.bundleHealth
import io.aequicor.heartbeat.platform.dibundle.createHeartbeatGraph
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

private val log = Log.tag("TrainSmoke")

/**
 * Headless smoke of a processed bundle train (`verifyShrunkTrain`): starts the production graph over the train on the
 * class path, opens a profile so the profile graphs of the bundles are created, and requires every bundle named in
 * the arguments after the report file to be healthy. The task runs it with the jars of the train, not with the classes
 * of this module. The findings go to the report file (`args[0]`); exit code 0 when all are healthy, 1 otherwise.
 */
fun main(args: Array<String>) {
    // Logs of the host (profile failures among them) go to the throwaway home of the run.
    Log.init(isDebug = true)
    val report = File(args.first())
    val findings = try {
        smoke(args.drop(1).toSet())
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
    try {
        runBlocking { graph.profileSessions.open(ProfileId("train-smoke")) }
    } finally {
        runBlocking { graph.profileSessions.close() }
    }
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
