package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class TrainPartTest {
    private val bundleImpls = setOf(":features:browser:impl", ":features:app-update:impl")

    @Test
    fun `third-party artifacts stay in the platform`() {
        assertEquals(TrainPart.Platform, trainPartOf(null, bundleImpls))
        assertEquals(TrainPart.Platform, trainPartOf(":platform-main:launcher", bundleImpls))
    }

    @Test
    fun `bundle assemblers and the impls they carry belong to bundles`() {
        assertEquals(TrainPart.Bundle, trainPartOf(":bundles:web", bundleImpls))
        assertEquals(TrainPart.Bundle, trainPartOf(":features:browser:impl", bundleImpls))
    }

    @Test
    fun `feature api modules are contracts at any depth`() {
        assertEquals(TrainPart.Contracts, trainPartOf(":features:app-update:api", bundleImpls))
        assertEquals(TrainPart.Contracts, trainPartOf(":features:ai-engine:connections:api", bundleImpls))
    }

    @Test
    fun `core, design system, the static graph and unowned impls are the foundation`() {
        listOf(
            ":core:bundles:api",
            ":design-system:components",
            ":platform-main:di-bundle",
            ":features:ai-studio:impl",
            ":features:ai-studio:screen-model",
        ).forEach { assertEquals(TrainPart.Foundation, trainPartOf(it, bundleImpls), it) }
    }

    @Test
    fun `jar names stay unless two libraries publish the same one`() {
        val a = java.io.File("/cache/a/saveable.jar")
        val b = java.io.File("/cache/b/saveable.jar")
        val c = java.io.File("/cache/c/other.jar")
        val names = uniqueJarNames(listOf("org.jetbrains.compose.runtime" to a, "androidx.compose.runtime" to b, "x" to c))
        assertEquals(setOf("org.jetbrains.compose.runtime-saveable.jar", "androidx.compose.runtime-saveable.jar", "other.jar"), names.keys)
    }

    @Test
    fun `only the launcher may bring project classes to the platform class path`() {
        val clean = sequenceOf(
            "io/aequicor/heartbeat/launcher/LauncherMain.class",
            "androidx/compose/runtime/Composer.class",
            "io/aequicor/heartbeat/data.txt",
        )
        assertEquals(emptyList(), projectClassesIn(clean))
        assertEquals(
            listOf("io/aequicor/heartbeat/core/common/Foo.class"),
            projectClassesIn(sequenceOf("io/aequicor/heartbeat/core/common/Foo.class")),
        )
    }
}
