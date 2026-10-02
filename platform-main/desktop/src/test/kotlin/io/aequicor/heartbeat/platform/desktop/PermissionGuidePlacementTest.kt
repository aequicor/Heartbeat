package io.aequicor.heartbeat.platform.desktop

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PermissionGuidePlacementTest {
    private val area = Rectangle(0, 25, 1440, 800)
    private val panel = Dimension(340, 120)

    @Test
    fun `panel sits centered below the settings window`() {
        assertEquals(
            Point(330, 637),
            permissionGuideLocation(Rectangle(200, 100, 600, 525), area, panel, GAP),
        )
    }

    @Test
    fun `a tall settings window puts the panel beside it, right first`() {
        assertEquals(
            Point(812, 625),
            permissionGuideLocation(Rectangle(200, 100, 600, 645), area, panel, GAP),
        )
    }

    @Test
    fun `a settings window at the right edge puts the panel on its left`() {
        assertEquals(
            Point(488, 625),
            permissionGuideLocation(Rectangle(840, 100, 600, 645), area, panel, GAP),
        )
    }

    @Test
    fun `a screen-wide settings window gets the panel over its bottom edge`() {
        assertEquals(
            Point(550, 693),
            permissionGuideLocation(Rectangle(0, 25, 1440, 800), area, panel, GAP),
        )
    }

    @Test
    fun `without a settings window the panel waits at the bottom center`() {
        assertEquals(Point(550, 693), permissionGuideLocation(null, area, panel, GAP))
    }

    @Test
    fun `the panel stays on a monitor left of the primary one`() {
        val left = Rectangle(-1920, 0, 1920, 1080)
        assertEquals(
            Point(-1920, 540),
            permissionGuideLocation(Rectangle(-2100, 200, 500, 328), left, panel, GAP),
        )
    }

    @Test
    fun `the innermost application bundle receives the permission`() {
        val simulator = "/Applications/Xcode.app/Contents/Applications/Simulator.app"
        assertEquals(File(simulator), applicationBundle(File("$simulator/Contents/MacOS/Simulator")))
        assertEquals(
            File("/Applications/Heartbeat.app"),
            applicationBundle(File("/Applications/Heartbeat.app/Contents/MacOS/Heartbeat")),
        )
        assertNull(applicationBundle(File("/usr/bin/java")))
    }

    private companion object {
        const val GAP = 12
    }
}
