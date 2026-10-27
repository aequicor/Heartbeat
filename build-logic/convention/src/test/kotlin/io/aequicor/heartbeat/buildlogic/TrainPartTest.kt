package io.aequicor.heartbeat.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class TrainPartTest {
    private val bundleImpls = setOf(":features:browser:impl", ":features:app-update:impl")

    @Test
    fun `third-party artifacts stay in the platform`() {
        assertEquals(TrainPart.Platform, trainPartOf(null, bundleImpls))
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
}
