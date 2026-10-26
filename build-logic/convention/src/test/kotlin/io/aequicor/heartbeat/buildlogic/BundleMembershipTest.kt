package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BundleMembershipTest {
    @Test
    fun `impl assembled by a single bundle is fine`() {
        val membership = mapOf(
            ":bundles:web" to listOf(":features:browser:impl", ":features:plant-uml-support:impl"),
            ":bundles:studio" to listOf(":features:ai-studio:impl"),
        )
        assertTrue(bundleMembershipViolations(membership).isEmpty())
    }

    @Test
    fun `impl assembled by two bundles is reported once per module`() {
        val membership = mapOf(
            ":bundles:web" to listOf(":features:browser:impl", ":features:browser:impl"),
            ":bundles:studio" to listOf(":features:browser:impl", ":features:ai-studio:impl"),
            ":bundles:shell" to listOf(":features:browser:impl"),
        )
        assertEquals(
            listOf(":features:browser:impl is assembled by :bundles:shell, :bundles:studio, :bundles:web"),
            bundleMembershipViolations(membership),
        )
    }

    @Test
    fun `every impl owned by one bundle or the foundation is fine`() {
        val membership = mapOf(":bundles:web" to listOf(":features:browser:impl"))
        val violations = bundleMembershipViolations(
            membership,
            foundation = listOf(":core:di:impl", ":features:ai-studio:impl"),
            impls = listOf(":core:di:impl", ":features:ai-studio:impl", ":features:browser:impl"),
        )
        assertTrue(violations.isEmpty(), violations.toString())
    }

    @Test
    fun `impl in a bundle and in the foundation graph is reported`() {
        val membership = mapOf(":bundles:web" to listOf(":features:browser:impl"))
        assertEquals(
            listOf(":features:browser:impl is assembled by :bundles:web, $FOUNDATION_OWNER"),
            bundleMembershipViolations(
                membership,
                foundation = listOf(":features:browser:impl"),
                impls = listOf(":features:browser:impl"),
            ),
        )
    }

    @Test
    fun `impl owned by nobody is reported`() {
        assertEquals(
            listOf(":features:orphan:impl is assembled by no bundle and not by the foundation"),
            bundleMembershipViolations(
                mapOf(":bundles:web" to listOf(":features:browser:impl")),
                foundation = listOf(":core:di:impl"),
                impls = listOf(":core:di:impl", ":features:browser:impl", ":features:orphan:impl"),
            ),
        )
    }

    @Test
    fun `compile-only and test configurations of the foundation own nothing`() {
        assertTrue(isFoundationOwnerConfiguration("commonMainImplementation"))
        assertTrue(isFoundationOwnerConfiguration("jvmMainApi"))
        assertFalse(isFoundationOwnerConfiguration("jvmMainCompileOnly"))
        assertFalse(isFoundationOwnerConfiguration("commonMainCompileOnly"))
        assertFalse(isFoundationOwnerConfiguration("commonMainCompileOnlyDependenciesMetadata"))
        assertFalse(isFoundationOwnerConfiguration("jvmTestImplementation"))
        assertFalse(isFoundationOwnerConfiguration("androidMainImplementation"))
    }
}
