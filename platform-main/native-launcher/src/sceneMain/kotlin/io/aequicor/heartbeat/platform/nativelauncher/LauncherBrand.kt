package io.aequicor.heartbeat.platform.nativelauncher

/** Vector coordinates in the original 128 × 128 brand artwork. */
internal data class LauncherPoint(val x: Double, val y: Double)

/** Geometry and brand pigments from assets/branding/heartbeat-app-icon.svg; no second ECG is overlaid. */
internal object LauncherBrand {
    const val BLUE = 0xF58239u
    const val VIOLET = 0xE84AA4u
    const val WHITE = 0xFFFFFFu
    val pulse = outline(26.0, 69.0) {
        line(42.0, 69.0)
        cubic(45.0, 69.0, 46.0, 70.0, 48.0, 74.0)
        line(50.0, 78.0)
        cubic(51.0, 81.0, 53.0, 78.0, 54.0, 74.0)
        line(60.0, 40.0)
        quadratic(61.0, 34.0, 62.0, 40.0)
        line(66.0, 85.0)
        quadratic(67.0, 91.0, 69.0, 85.0)
        line(72.0, 73.0)
        quadratic(73.0, 70.0, 77.0, 69.0)
        cubic(82.0, 68.0, 82.0, 61.0, 86.0, 61.0)
        cubic(90.0, 61.0, 92.0, 69.0, 98.0, 69.0)
        line(103.0, 69.0)
    }
    val shell = outline(36.0, 19.0) {
        line(70.0, 9.0)
        cubic(78.0, 7.0, 85.0, 10.0, 91.0, 17.0)
        line(113.0, 42.0)
        cubic(120.0, 50.0, 122.0, 57.0, 120.0, 66.0)
        line(111.0, 96.0)
        cubic(109.0, 105.0, 102.0, 111.0, 93.0, 113.0)
        line(58.0, 119.0)
        cubic(48.0, 121.0, 41.0, 117.0, 35.0, 110.0)
        line(13.0, 84.0)
        cubic(7.0, 77.0, 6.0, 70.0, 9.0, 61.0)
        line(18.0, 35.0)
        cubic(21.0, 26.0, 28.0, 22.0, 36.0, 19.0)
    }

    private fun outline(x: Double, y: Double, block: BrandOutline.() -> Unit): List<LauncherPoint> =
        BrandOutline(x, y).apply(block).points.toList()
}

// Standard quadratic-to-cubic conversion and cubic Bernstein polynomial coefficients.
@Suppress("MagicNumber")
private class BrandOutline(x: Double, y: Double) {
    val points = mutableListOf(LauncherPoint(x, y))

    fun line(x: Double, y: Double) {
        points += LauncherPoint(x, y)
    }

    fun quadratic(cx: Double, cy: Double, x: Double, y: Double) {
        val from = points.last()
        cubic(
            from.x + (cx - from.x) * 2 / 3,
            from.y + (cy - from.y) * 2 / 3,
            x + (cx - x) * 2 / 3,
            y + (cy - y) * 2 / 3,
            x,
            y,
        )
    }

    // A cubic Bezier is defined by two control points and one endpoint, like the source SVG.
    @Suppress("LongParameterList")
    fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        val from = points.last()
        for (index in 1..CURVE_STEPS) {
            val t = index.toDouble() / CURVE_STEPS
            val u = 1 - t
            points += LauncherPoint(
                u * u * u * from.x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
                u * u * u * from.y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y,
            )
        }
    }

    companion object {
        private const val CURVE_STEPS = 48
    }
}
