package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AffectedBundlesTest {
    private val dirs = mapOf(
        ":core:common" to "core/common",
        ":features:browser:api" to "features/browser/api",
        ":features:browser:impl" to "features/browser/impl",
        ":features:checklist:impl" to "features/checklist/impl",
        ":bundles:web" to "bundles/web",
        ":bundles:studio" to "bundles/studio",
    )
    private val deps = mapOf(
        ":features:browser:api" to listOf(":core:common"),
        ":features:browser:impl" to listOf(":features:browser:api", ":core:common"),
        ":features:checklist:impl" to listOf(":core:common"),
        ":bundles:web" to listOf(":features:browser:impl"),
        ":bundles:studio" to listOf(":features:checklist:impl", ":features:browser:api"),
    )
    private val bundles = setOf(":bundles:web", ":bundles:studio")

    private fun affected(vararg paths: String) = affectedBundles(paths.toList(), dirs, deps, bundles)

    @Test
    fun `impl change affects only its bundle`() {
        assertEquals(listOf(":bundles:web"), affected("features/browser/impl/src/Foo.kt"))
    }

    @Test
    fun `api change reaches every dependant bundle transitively`() {
        assertEquals(listOf(":bundles:studio", ":bundles:web"), affected("features/browser/api/src/Foo.kt"))
    }

    @Test
    fun `core change affects all bundles that depend on it`() {
        assertEquals(listOf(":bundles:studio", ":bundles:web"), affected("core/common/build.gradle.kts"))
    }

    @Test
    fun `build files affect all bundles`() {
        assertEquals(bundles.sorted(), affected("gradle/libs.versions.toml"))
        assertEquals(bundles.sorted(), affected("build-logic/convention/src/main/Foo.kt"))
        assertEquals(bundles.sorted(), affected("settings.gradle.kts"))
    }

    @Test
    fun `files outside projects affect nothing`() {
        assertTrue(affected("docs/readme.md", ".github/workflows/ci.yml", "features/unknown/src/A.kt").isEmpty())
    }

    @Test
    fun `directory prefix must match whole segment`() {
        assertTrue(affected("features/browser/implementation/A.kt").isEmpty())
    }

    @Test
    fun `paths are normalized and blank entries ignored`() {
        assertEquals(listOf(":bundles:studio"), affected("", "  ", ".\\features\\checklist\\impl\\A.kt"))
    }
}
