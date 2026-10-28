package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainSdkTest {
    private val bundleImpls = setOf(":features:browser:impl")
    private val partOf: (String) -> TrainPart = { trainPartOf(it, bundleImpls) }

    @Test
    fun `bundle on contracts and sdk modules is fine`() {
        val violations = trainSdkViolations(
            bundleDependencies = mapOf(":bundles:web" to listOf(":core:logging", ":features:browser:api")),
            apiDependencies = mapOf(
                ":core:logging" to emptyList(),
                ":features:browser:api" to listOf(":core:state-machine:api"),
                ":core:state-machine:api" to emptyList(),
            ),
            sdk = setOf(":core:logging", ":core:state-machine:api"),
            partOf = partOf,
        )
        assertTrue(violations.isEmpty(), violations.toString())
    }

    @Test
    fun `direct dependency outside the sdk is reported`() {
        val violations = trainSdkViolations(
            bundleDependencies = mapOf(":bundles:web" to listOf(":core:network:impl")),
            apiDependencies = mapOf(":core:network:impl" to emptyList()),
            sdk = emptySet(),
            partOf = partOf,
        )
        assertEquals(listOf(":bundles:web reaches :core:network:impl (via :bundles:web), which is not in the SDK"), violations)
    }

    @Test
    fun `api of contracts and sdk modules is reached transitively`() {
        val violations = trainSdkViolations(
            bundleDependencies = mapOf(":bundles:web" to listOf(":features:browser:api", ":design-system:theme")),
            apiDependencies = mapOf(
                ":features:browser:api" to listOf(":core:mvi"),
                ":design-system:theme" to listOf(":design-system:tokens"),
                ":core:mvi" to emptyList(),
                ":design-system:tokens" to emptyList(),
            ),
            sdk = setOf(":design-system:theme"),
            partOf = partOf,
        )
        assertEquals(
            listOf(
                ":bundles:web reaches :core:mvi (via :features:browser:api), which is not in the SDK",
                ":bundles:web reaches :design-system:tokens (via :design-system:theme), which is not in the SDK",
            ),
            violations,
        )
    }

    @Test
    fun `api of a module outside the sdk is not followed`() {
        val violations = trainSdkViolations(
            bundleDependencies = mapOf(":bundles:web" to listOf(":core:network:impl")),
            apiDependencies = mapOf(":core:network:impl" to listOf(":core:network:api"), ":core:network:api" to emptyList()),
            sdk = emptySet(),
            partOf = partOf,
        )
        assertEquals(1, violations.size, violations.toString())
    }

    @Test
    fun `third-party and bundle modules are not foundation`() {
        val violations = trainSdkViolations(
            bundleDependencies = mapOf(":bundles:web" to listOf(":features:browser:impl", ":platform-main:launcher")),
            apiDependencies = mapOf(":features:browser:impl" to emptyList(), ":platform-main:launcher" to emptyList()),
            sdk = emptySet(),
            partOf = partOf,
        )
        assertTrue(violations.isEmpty(), violations.toString())
    }

    @Test
    fun `sdk entry that is not a foundation module is reported`() {
        val violations = trainSdkViolations(
            bundleDependencies = emptyMap(),
            apiDependencies = mapOf(":features:browser:api" to emptyList(), ":core:logging" to emptyList()),
            sdk = setOf(":features:browser:api", ":core:missing", ":core:logging"),
            partOf = partOf,
        )
        assertEquals(
            listOf(
                ":core:missing is in the SDK but is not a foundation module",
                ":features:browser:api is in the SDK but is not a foundation module",
            ),
            violations,
        )
    }

    @Test
    fun `dependencies behind another impl are reached too`() {
        val graph = mapOf(
            ":features:a:impl" to listOf(":features:b:impl", ":core:logging"),
            ":features:b:impl" to listOf(":core:network:impl", ":features:a:impl"),
            ":core:network:impl" to listOf(":core:secrets"),
        )
        val reached = reachedImpls(listOf(":features:a:impl")) { graph[it].orEmpty() }
        assertEquals(
            setOf(":features:b:impl", ":core:logging", ":core:network:impl", ":features:a:impl", ":core:secrets"),
            reached.toSet(),
        )
    }

    @Test
    fun `desktop main configurations exclude tests and other platforms`() {
        listOf("api", "implementation", "compileOnly", "commonMainApi", "jvmMainImplementation", "jvmMainCompileOnly")
            .forEach { assertTrue(isDesktopMainConfiguration(it), it) }
        listOf(
            "testImplementation", "jvmTestImplementation", "androidMainImplementation", "iosMainApi",
            "commonMainApiDependenciesMetadata",
        ).forEach { assertFalse(isDesktopMainConfiguration(it), it) }
        assertTrue(isApiConfiguration("commonMainApi"))
        assertTrue(isApiConfiguration("api"))
        assertFalse(isApiConfiguration("jvmMainImplementation"))
    }
}
