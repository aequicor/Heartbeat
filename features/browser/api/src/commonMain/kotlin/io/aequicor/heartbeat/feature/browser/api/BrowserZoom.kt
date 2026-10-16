package io.aequicor.heartbeat.feature.browser.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Page zoom as in desktop Chrome: Cmd/Ctrl with the wheel or the zoom keys, the address bar's zoom badge, and a level
 * remembered for each site in the profile. Desktop only, under [BrowserEnabled].
 */
public val BrowserPageZoom: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "browser.page_zoom",
    description = "Масштаб страниц во встроенном браузере",
    default = false,
)

/** Zoom commands of Chrome's keys and zoom badge. */
public enum class BrowserZoom {
    /** The next larger preset level. */
    In,

    /** The next smaller preset level. */
    Out,

    /** Back to [BROWSER_DEFAULT_ZOOM], forgetting the site's level. */
    Reset,
}

/** The zoom of a page nobody zoomed, percent. */
public const val BROWSER_DEFAULT_ZOOM: Int = 100

/** Chrome's preset zoom levels in percent, ascending: 25 % to 500 %. */
public val BrowserZoomLevels: List<Int> =
    listOf(25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300, 400, 500)

/**
 * The level [zoom] moves [current] to. Like Chrome, a level between presets moves to the nearest preset in the
 * command's direction; the smallest and largest presets stay where they are.
 */
public fun browserZoomStep(current: Int, zoom: BrowserZoom): Int = when (zoom) {
    BrowserZoom.In -> BrowserZoomLevels.firstOrNull { it > current } ?: BrowserZoomLevels.last().coerceAtLeast(current)
    BrowserZoom.Out -> BrowserZoomLevels.lastOrNull { it < current } ?: BrowserZoomLevels.first().coerceAtMost(current)
    BrowserZoom.Reset -> BROWSER_DEFAULT_ZOOM
}
