package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.resources.HbLocale
import io.aequicor.heartbeat.ds.resources.HbResources
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AssetCatalogUiTest {
    @Test
    fun `search and category filters intersect and recover from no results`() = runSkikoComposeUiTest(
        size = Size(960f, 800f),
    ) {
        setContent {
            HbResources(HbLocale.English) { HbTheme { IconsCatalog(modifier = Modifier.fillMaxSize()) } }
        }
        onNodeWithTag("icon-count").assertTextEquals("Matching icons: 110 / 110")
        val search = onNodeWithTag("icon-search")
        search.performTextReplacement("  fOlDeR  ")
        onNodeWithTag("icon-count").assertTextEquals("Matching icons: 3 / 110")
        onNodeWithText("FolderPlus").assertIsDisplayed()
        onNodeWithText("Media").performClick()
        onNodeWithText("No icons found. Try a different name or category.").assertIsDisplayed()
        onNodeWithText("All icons").performClick()
        onNodeWithText("FolderOpen").assertIsDisplayed()
        search.performTextReplacement("does-not-exist")
        onNodeWithTag("icon-count").assertTextEquals("Matching icons: 0 / 110")
        search.performTextReplacement("")
        onNodeWithTag("icon-count").assertTextEquals("Matching icons: 110 / 110")
    }

    @Test
    fun `asset pages are reachable from compact navigation in both languages`() = runSkikoComposeUiTest(
        size = Size(390f, 844f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        if (onAllNodesWithText("EN").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("EN").performClick()
        onNodeWithText("Icons").performScrollTo().performClick()
        onNodeWithTag("icon-search").performTextReplacement("Folder")
        onNodeWithText("FolderPlus").performScrollTo().assertIsDisplayed()
        saveAssetPreview("compact-icons", captureToImage().toAwtImage())
        onNodeWithText("Illustrations").performScrollTo().performClick()
        onNodeWithText("Welcome").assertIsDisplayed()
        onNodeWithText("RU").performClick()
        onNodeWithText("Добро пожаловать").assertIsDisplayed()
        onNodeWithText("HbIllustrationKind.Upload").performScrollTo().assertIsDisplayed()
        saveAssetPreview("compact-illustrations-ru", captureToImage().toAwtImage())
    }

    @Test
    fun `illustration gallery renders all scenes in both themes`() {
        listOf(false, true).forEach { dark ->
            runSkikoComposeUiTest(size = Size(940f, 1080f)) {
                setContent {
                    HbResources(HbLocale.English) {
                        HbTheme(darkTheme = dark) {
                            Box(modifier = Modifier.fillMaxSize().background(HbTheme.colors.background)) {
                                IllustrationsCatalog(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
                onNodeWithText("Welcome").assertIsDisplayed()
                onNodeWithText("HbIllustrationKind.Upload").assertIsDisplayed()
                saveAssetPreview("illustrations-${if (dark) "dark" else "light"}", captureToImage().toAwtImage())
            }
        }
    }

    @Test
    fun `narrow RTL gallery can reach its last row`() = runSkikoComposeUiTest(size = Size(320f, 720f)) {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HbResources(HbLocale.English) { HbTheme { IconsCatalog(modifier = Modifier.fillMaxSize()) } }
            }
        }
        onNodeWithTag("icon-search").performTextReplacement("Arrow")
        onNodeWithText("ArrowDown").performScrollTo().assertIsDisplayed()
        saveAssetPreview("compact-icons-rtl", captureToImage().toAwtImage())
    }

    @Test
    fun `every public icon is present exactly once in the grouped catalog`() {
        val declared = HbIcons.javaClass.methods.filter { it.returnType == ImageVector::class.java }
            .map { it.invoke(HbIcons) as ImageVector }
        val catalog = CatalogIconGroups.flatMap { it.icons }
        assertEquals(declared.map { it.name }.toSet(), HbIcons.All.map { it.name }.toSet())
        assertEquals(HbIcons.All.map { it.name }.toSet(), catalog.map { it.name }.toSet())
        assertEquals(catalog.size, catalog.distinctBy { it.name }.size)
    }
}

private fun saveAssetPreview(name: String, image: java.awt.image.BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "$name.png"))) { "PNG encoder is unavailable" }
}
