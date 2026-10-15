package io.aequicor.heartbeat.feature.browser.impl.presentation

import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import kotlin.math.abs

/** JCEF hides the browser child, while its clipping group can remain visible and attached to the window. */
internal fun <T> visibleNativeDocumentGroups(
    root: T,
    children: (T) -> List<T>,
    isDocumentGroup: (T) -> Boolean,
    isHidden: (T) -> Boolean,
): List<T> {
    val pending = ArrayDeque<T>()
    val matches = mutableListOf<T>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val view = pending.removeFirst()
        if (isHidden(view)) continue
        val visibleChildren = children(view).filterNot(isHidden)
        if (isDocumentGroup(view) && visibleChildren.isNotEmpty()) matches.add(view)
        pending.addAll(visibleChildren)
    }
    return matches
}

/** A frame mismatch rejects the sole candidate; geometry never chooses between multiple native peers. */
internal fun Rectangle2D.matchesDocumentFrame(expected: Rectangle): Boolean {
    if (isEmpty || expected.isEmpty) return false
    val actual = listOf(x, y, width, height)
    val wanted = listOf(
        expected.x.toDouble(),
        expected.y.toDouble(),
        expected.width.toDouble(),
        expected.height.toDouble(),
    )
    return actual.zip(wanted).all { (value, target) -> abs(value - target) <= FRAME_ROUNDING_TOLERANCE }
}

private const val FRAME_ROUNDING_TOLERANCE = 1.0
