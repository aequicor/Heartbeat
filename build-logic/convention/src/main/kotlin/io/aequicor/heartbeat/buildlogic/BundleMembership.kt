package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** Owner name of the impls that the desktop static graph (`:platform-main:di-bundle`, common and JVM) compiles in. */
internal const val FOUNDATION_OWNER = "foundation (:platform-main:di-bundle)"

/** Source sets of the desktop static graph of the foundation. */
private val FOUNDATION_SOURCE_SETS = listOf("commonMain", "jvmMain")

/**
 * Whether a configuration of `:platform-main:di-bundle` puts its project dependencies into the desktop static graph:
 * the common and JVM main source sets, without tests and without `compileOnly` (compiled against, never shipped, so
 * its Metro contributions do not reach the graph and it owns nothing).
 */
internal fun isFoundationOwnerConfiguration(name: String): Boolean =
    FOUNDATION_SOURCE_SETS.any { name.startsWith(it) } && "Test" !in name && "CompileOnly" !in name

/**
 * Impl modules that do not have exactly one owner on desktop. [membership] is `bundle path -> impl module paths`,
 * [foundation] the impls of the desktop static graph, [impls] every `…:impl` module of the build.
 * An impl lives in exactly one bundle or in the foundation: two copies of its Metro contributions would create two
 * instances, and an impl nobody assembles silently drops its feature from the desktop app.
 */
internal fun bundleMembershipViolations(
    membership: Map<String, List<String>>,
    foundation: List<String> = emptyList(),
    impls: List<String> = emptyList(),
): List<String> {
    val owners = membership.entries
        .flatMap { (bundle, members) -> members.distinct().map { impl -> impl to bundle } }
        .plus(foundation.distinct().map { impl -> impl to FOUNDATION_OWNER })
        .groupBy({ it.first }, { it.second })
    val shared = owners.filterValues { it.size > 1 }
        .map { (impl, by) -> "$impl is assembled by ${by.sorted().joinToString()}" }
    val orphans = impls.distinct().filter { it !in owners }
        .map { impl -> "$impl is assembled by no bundle and not by the foundation" }
    return (shared + orphans).sorted()
}

/**
 * Root task `verifyBundleMembership`: fails when an impl module does not have exactly one desktop owner — one
 * `:bundles:*` assembler or the foundation graph.
 */
abstract class VerifyBundleMembership : DefaultTask() {
    /** `bundle path -> impl module paths`, filled by each `heartbeat.bundle` project. */
    @get:Input
    abstract val membership: MapProperty<String, List<String>>

    /** Impl modules compiled into the desktop static graph of the foundation. */
    @get:Input
    abstract val foundation: ListProperty<String>

    /** Every `…:impl` module of the build. */
    @get:Input
    abstract val impls: ListProperty<String>

    @TaskAction
    fun verify() {
        val violations = bundleMembershipViolations(membership.get(), foundation.get(), impls.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                "An impl module must belong to exactly one bundle or to the foundation:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
