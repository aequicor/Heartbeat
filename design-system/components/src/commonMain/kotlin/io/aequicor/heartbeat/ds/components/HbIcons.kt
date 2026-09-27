package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions

private const val ICON_VIEWPORT = 20f
private const val ICON_STROKE = 1.4f

/** Compact, rounded outline icons shared by every platform kit. Geometry is independent of fonts. */
public object HbIcons {
    public val Home: ImageVector = outlineIcon(
        "Home",
        "M2.5,9 L9,3.5 Q10,2.7 11,3.5 L17.5,9 M4.5,7.5 V15.5 Q4.5,17 6,17 " +
            "H8 V12 H12 V17 H14 Q15.5,17 15.5,15.5 V7.5",
    )
    public val HomeFilled: ImageVector = filledIcon(
        "HomeFilled",
        "M2,8.7 L8.8,3 Q10,2 11.2,3 L18,8.7 Q18.8,9.5 17.7,10.2 H16.5 V15.8 " +
            "Q16.5,17.5 14.8,17.5 H12 V12.7 Q12,12 11.3,12 H8.7 Q8,12 8,12.7 V17.5 " +
            "H5.2 Q3.5,17.5 3.5,15.8 V10.2 H2.3 Q1.2,9.5 2,8.7 Z",
    )
    public val History: ImageVector = outlineIcon(
        "History",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M10,5.5 V10 L7.5,12",
    )
    public val Library: ImageVector = outlineIcon(
        "Library",
        "M3.5,3 H5 Q6,3 6,4 V16 Q6,17 5,17 H3.5 Q2.5,17 2.5,16 V4 Q2.5,3 3.5,3 Z " +
            "M8.5,3 H10 Q11,3 11,4 V16 Q11,17 10,17 H8.5 Q7.5,17 7.5,16 V4 Q7.5,3 8.5,3 Z " +
            "M13.2,3.6 L14.7,3.3 Q15.7,3.1 15.9,4.1 L18.2,15.6 Q18.4,16.6 17.4,16.8 " +
            "L15.9,17.1 Q14.9,17.3 14.7,16.3 L12.4,4.8 Q12.2,3.8 13.2,3.6 Z",
    )
    public val Images: ImageVector = outlineIcon(
        "Images",
        "M8,5 V4 Q8,2.5 9.5,2.5 H15.5 Q17,2.5 17,4 V11 Q17,12.5 15.5,12.5 H15 " +
            "M4.5,6 H11.5 Q13,6 13,7.5 V15.5 Q13,17 11.5,17 H4.5 Q3,17 3,15.5 V7.5 " +
            "Q3,6 4.5,6 Z M3,14 L6.5,10.5 L10,14 L11.5,12.5 L13,14 " +
            "M10.2,9 A0.7,0.7 0,1 1,8.8,9 A0.7,0.7 0,1 1,10.2,9 Z",
    )
    public val Chat: ImageVector = outlineIcon(
        "Chat",
        "M17.5,9.5 C17.5,13.6 14.2,16.5 10,16.5 Q8,16.5 6.2,15.7 L2.5,17 " +
            "L3.7,13.5 Q2.5,11.8 2.5,9.5 C2.5,5.4 5.8,2.5 10,2.5 " +
            "C14.2,2.5 17.5,5.4 17.5,9.5 Z M6.5,8 H13.5 M6.5,11.5 H11",
    )
    public val More: ImageVector = filledIcon(
        "More",
        "M4.7,10 A1.2,1.2 0,1 1,2.3,10 A1.2,1.2 0,1 1,4.7,10 Z " +
            "M11.2,10 A1.2,1.2 0,1 1,8.8,10 A1.2,1.2 0,1 1,11.2,10 Z " +
            "M17.7,10 A1.2,1.2 0,1 1,15.3,10 A1.2,1.2 0,1 1,17.7,10 Z",
    )
    public val ArrowLeft: ImageVector = outlineIcon("ArrowLeft", "M16,10 H4 M9,5 L4,10 L9,15", autoMirror = true)
    public val ArrowRight: ImageVector = outlineIcon("ArrowRight", "M4,10 H16 M11,5 L16,10 L11,15", autoMirror = true)
    public val ArrowUp: ImageVector = outlineIcon("ArrowUp", "M10,16 V4 M5,9 L10,4 L15,9")
    public val ArrowDown: ImageVector = outlineIcon("ArrowDown", "M10,4 V16 M5,11 L10,16 L15,11")
    public val ChevronRight: ImageVector = outlineIcon("ChevronRight", "M7.5,5 L12.5,10 L7.5,15", autoMirror = true)
    public val ChevronDown: ImageVector = outlineIcon("ChevronDown", "M5,7.5 L10,12.5 L15,7.5")
    public val Sidebar: ImageVector = outlineIcon(
        "Sidebar",
        "M4,3.5 H16 Q17.5,3.5 17.5,5 V15 Q17.5,16.5 16,16.5 H4 Q2.5,16.5 2.5,15 " +
            "V5 Q2.5,3.5 4,3.5 Z M8,3.5 V16.5",
        autoMirror = true,
    )
    public val Folder: ImageVector = outlineIcon(
        "Folder",
        "M2.5,7 V5 Q2.5,3.5 4,3.5 H7 L9,5.5 H16 Q17.5,5.5 17.5,7 V14.5 " +
            "Q17.5,16 16,16 H4 Q2.5,16 2.5,14.5 Z M2.5,8.5 H17.5",
    )
    public val Laptop: ImageVector = outlineIcon(
        "Laptop",
        "M4,13.5 V5 Q4,3.5 5.5,3.5 H14.5 Q16,3.5 16,5 V13.5 " +
            "M2,13.5 H18 L17,16 H3 Z M8,13.5 V14 H12 V13.5",
    )
    public val Branch: ImageVector = outlineIcon(
        "Branch",
        "M7,4 A1.75,1.75 0,1 1,3.5,4 A1.75,1.75 0,1 1,7,4 Z " +
            "M7,16 A1.75,1.75 0,1 1,3.5,16 A1.75,1.75 0,1 1,7,16 Z " +
            "M16.5,5 A1.75,1.75 0,1 1,13,5 A1.75,1.75 0,1 1,16.5,5 Z " +
            "M5.25,5.75 V14.25 M14.75,6.75 C14.75,12 5.25,8 5.25,14.25",
    )
    public val Plus: ImageVector = outlineIcon("Plus", "M10,4 V16 M4,10 H16")
    public val Plan: ImageVector = outlineIcon(
        "Plan",
        "M5,3.5 H15 Q16.5,3.5 16.5,5 V15 Q16.5,16.5 15,16.5 H5 Q3.5,16.5 3.5,15 " +
            "V5 Q3.5,3.5 5,3.5 Z M7,7 H13 M7,10 H13 M7,13 H10",
    )
    public val Sparkles: ImageVector = outlineIcon(
        "Sparkles",
        "M9,3 Q9.5,9.5 16,10 Q9.5,10.5 9,17 Q8.5,10.5 2,10 Q8.5,9.5 9,3 Z " +
            "M15.5,2 V6 M13.5,4 H17.5",
    )
    public val Copy: ImageVector = outlineIcon(
        "Copy",
        "M7,6 V4 Q7,2.5 8.5,2.5 H15.5 Q17,2.5 17,4 V11 Q17,12.5 15.5,12.5 H14 " +
            "M4.5,7 H11.5 Q13,7 13,8.5 V15.5 Q13,17 11.5,17 H4.5 Q3,17 3,15.5 V8.5 Q3,7 4.5,7 Z",
    )
    public val Check: ImageVector = outlineIcon("Check", "M4,10 L8,14 L16,6")
    public val Alert: ImageVector = outlineIcon(
        "Alert",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M10,5.5 V10.5 M10,14 V14.1",
    )
    public val Stop: ImageVector = filledIcon(
        "Stop",
        "M6,4.5 H14 Q15.5,4.5 15.5,6 V14 Q15.5,15.5 14,15.5 H6 Q4.5,15.5 4.5,14 " +
            "V6 Q4.5,4.5 6,4.5 Z",
    )
}

private fun outlineIcon(name: String, path: String, autoMirror: Boolean = false): ImageVector =
    iconBuilder(name, autoMirror).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        stroke = SolidColor(HbColors.Light.textPrimary),
        strokeLineWidth = ICON_STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

private fun filledIcon(name: String, path: String): ImageVector = iconBuilder(name).addPath(
    pathData = PathParser().parsePathString(path).toNodes(),
    fill = SolidColor(HbColors.Light.textPrimary),
).build()

private fun iconBuilder(name: String, autoMirror: Boolean = false): ImageVector.Builder = ImageVector.Builder(
    name = name,
    defaultWidth = HbDimensions().iconSize,
    defaultHeight = HbDimensions().iconSize,
    viewportWidth = ICON_VIEWPORT,
    viewportHeight = ICON_VIEWPORT,
    autoMirror = autoMirror,
)
