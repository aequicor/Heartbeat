package io.aequicor.heartbeat.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Impl modules that more than one bundle assembles, from `bundle path -> impl module paths`.
 * An impl lives in exactly one bundle: two copies of its Metro contributions would create two instances.
 */
internal fun bundleMembershipViolations(membership: Map<String, List<String>>): List<String> =
    membership.entries
        .flatMap { (bundle, impls) -> impls.distinct().map { impl -> impl to bundle } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
        .map { (impl, bundles) -> "$impl is assembled by ${bundles.sorted().joinToString()}" }
        .sorted()

/** Root task `verifyBundleMembership`: fails when an impl module belongs to more than one `:bundles:*` assembler. */
abstract class VerifyBundleMembership : DefaultTask() {
    /** `bundle path -> impl module paths`, filled by each `heartbeat.bundle` project. */
    @get:Input
    abstract val membership: MapProperty<String, List<String>>

    @TaskAction
    fun verify() {
        val violations = bundleMembershipViolations(membership.get())
        if (violations.isNotEmpty()) {
            throw GradleException(
                "An impl module must belong to exactly one bundle:\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
