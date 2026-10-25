package io.aequicor.heartbeat.platform.dibundle

import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.impl.BundleHost
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseCapturePresentation
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermissionGuide
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Exports of a `studio` bundle whose computer use breaks when the host reads it. */
interface BrokenStudioExports {
    val capturePresentation: ComputerUseCapturePresentation
    val permissionGuide: ComputerUsePermissionGuide
}

/** A failed or absent `studio` bundle degrades the main window's computer-use services instead of crashing it. */
class StudioImportFallbackTest {
    private val brokenStudio = object : BundleEntry {
        override val id = BundleId("studio")
        override val importsType: KClass<*>? = null
        override val exportTypes = listOf(BrokenStudioExports::class)
        override fun createGraph(foundation: Any, imports: Any): Any = object : BrokenStudioExports {
            override val capturePresentation: ComputerUseCapturePresentation get() = error("studio is broken")
            override val permissionGuide: ComputerUsePermissionGuide get() = error("studio is broken")
        }
    }

    @Test
    fun `absent studio gives disabled computer-use services`() = runTest {
        val host = BundleHost(emptyList()) { Unit }
        val presentation = BundleImportBindings.capturePresentation(host)
        val guide = BundleImportBindings.permissionGuide(host)
        assertSame(UnavailableCapturePresentation, presentation)
        assertSame(UnavailablePermissionGuide, guide)
        presentation.register { AutoCloseable {} }.close()
        assertEquals(1, presentation.withoutPresentation { 1 })
        assertNull(guide.guide.value)
        guide.dismiss()
    }

    @Test
    fun `studio failing on read is failed and the window gets the fallbacks`() {
        val host = BundleHost(listOf(brokenStudio)) { Unit }
        assertSame(UnavailableCapturePresentation, BundleImportBindings.capturePresentation(host))
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("studio")))
        assertSame(UnavailablePermissionGuide, BundleImportBindings.permissionGuide(host))
    }
}
