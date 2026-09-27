package io.aequicor.heartbeat.ds.components

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import io.aequicor.heartbeat.ds.tokens.HbColors
import io.aequicor.heartbeat.ds.tokens.HbDimensions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
    public val ChevronLeft: ImageVector = outlineIcon("ChevronLeft", "M12.5,5 L7.5,10 L12.5,15", autoMirror = true)
    public val ChevronUp: ImageVector = outlineIcon("ChevronUp", "M5,12.5 L10,7.5 L15,12.5")
    public val Grid: ImageVector = outlineIcon(
        "Grid",
        "M3,3 H8 V8 H3 Z M12,3 H17 V8 H12 Z M3,12 H8 V17 H3 Z M12,12 H17 V17 H12 Z",
    )
    public val Menu: ImageVector = outlineIcon("Menu", "M3,5 H17 M3,10 H17 M3,15 H17")
    public val ExternalLink: ImageVector = outlineIcon(
        "ExternalLink",
        "M11,3 H17 V9 M17,3 L9,11 M8,4 H5 Q3,4 3,6 V15 Q3,17 5,17 H14 Q16,17 16,15 V12",
    )
    public val Maximize: ImageVector = outlineIcon("Maximize", "M3,8 V3 H8 M12,3 H17 V8 M17,12 V17 H12 M8,17 H3 V12")
    public val Minimize: ImageVector = outlineIcon("Minimize", "M3,8 H8 V3 M12,3 V8 H17 M17,12 H12 V17 M8,17 V12 H3")
    public val Minus: ImageVector = outlineIcon("Minus", "M4,10 H16")
    public val Close: ImageVector = outlineIcon("Close", "M5,5 L15,15 M15,5 L5,15")
    public val Search: ImageVector = outlineIcon(
        "Search",
        "M14,8.5 A5.5,5.5 0,1 1,3,8.5 A5.5,5.5 0,1 1,14,8.5 Z M12.5,12.5 L17,17",
    )
    public val Edit: ImageVector = outlineIcon(
        "Edit",
        "M12,4 L16,8 M3,17 L4,12 L13,3 Q14,2 15,3 L17,5 Q18,6 17,7 L8,16 Z",
    )
    public val Clipboard: ImageVector = outlineIcon(
        "Clipboard",
        "M7,4 H5 Q3.5,4 3.5,5.5 V16 Q3.5,17.5 5,17.5 H15 Q16.5,17.5 16.5,16 V5.5 Q16.5,4 15,4 H13 " +
            "M8,2.5 H12 Q13,2.5 13,3.5 V5 H7 V3.5 Q7,2.5 8,2.5 Z",
    )
    public val Trash: ImageVector = outlineIcon(
        "Trash",
        "M3,5 H17 M7,5 V3 H13 V5 M5,5 L6,17 H14 L15,5 M8.5,8 V14 M11.5,8 V14",
    )
    public val Download: ImageVector = outlineIcon(
        "Download",
        "M10,3 V12 M6,8 L10,12 L14,8 M3,12 V16 Q3,17 4,17 H16 Q17,17 17,16 V12",
    )
    public val Upload: ImageVector = outlineIcon(
        "Upload",
        "M10,12 V3 M6,7 L10,3 L14,7 M3,12 V16 Q3,17 4,17 H16 Q17,17 17,16 V12",
    )
    public val Refresh: ImageVector = outlineIcon(
        "Refresh",
        "M16.5,7 A7,7 0,0 0,4,5 M3,2 V6 H7 M3.5,13 A7,7 0,0 0,16,15 M17,18 V14 H13",
    )
    public val Undo: ImageVector = outlineIcon("Undo", "M7,4 L3,8 L7,12 M3,8 H12 A5,5 0,0 1,12,18", autoMirror = true)
    public val Redo: ImageVector = outlineIcon("Redo", "M13,4 L17,8 L13,12 M17,8 H8 A5,5 0,0 0,8,18", autoMirror = true)
    public val Filter: ImageVector = outlineIcon("Filter", "M3,3 H17 L12,10 V16 L8,18 V10 Z")
    public val Sort: ImageVector = outlineIcon(
        "Sort",
        "M4,4 V16 M2,14 L4,16 L6,14 M9,5 H17 M9,10 H15 M9,15 H12",
    )
    public val Share: ImageVector = outlineIcon(
        "Share",
        "M7,9 L13,5 M7,11 L13,15 M7,10 A2,2 0,1 1,3,10 A2,2 0,1 1,7,10 Z M17,4 A2,2 0,1 1,13,4 A2,2 " +
            "0,1 1,17,4 Z M17,16 A2,2 0,1 1,13,16 A2,2 0,1 1,17,16 Z",
    )
    public val Link: ImageVector = outlineIcon(
        "Link",
        "M8,12 L12,8 M7,8 L5,8 Q2,8 2,11 V14 Q2,17 5,17 H8 Q11,17 11,14 V13 M9,7 V6 Q9,3 12,3 H15 " +
            "Q18,3 18,6 V9 Q18,12 15,12 H13",
    )
    public val Pin: ImageVector = outlineIcon("Pin", "M7,3 H13 L12,8 L15,11 V12 H5 V11 L8,8 Z M10,12 V18")
    public val Unpin: ImageVector = outlineIcon(
        "Unpin",
        "M3,3 L17,17 M9,3 H13 L12,8 M8,8 L5,11 V12 H12 M10,12 V18",
    )
    public val MoreVertical: ImageVector = outlineIcon("MoreVertical", "M10,3 V3.1 M10,10 V10.1 M10,17 V17.1")
    public val Settings: ImageVector = outlineIcon(
        "Settings",
        "M8,2 H12 L12.5,4.5 L15,6 L17.5,5.5 L19,9 L17,10.5 L16.5,13 L17.5,15 L14.5,17.5 L12.5,16 " +
            "L10,16.5 L8,18 L5,16 L5.5,13.5 L4,11.5 L1.5,10.5 L2.5,7 L5,7 L7,5 Z M13,10 A3,3 0,1 1,7,10 " +
            "A3,3 0,1 1,13,10 Z",
    )
    public val Sliders: ImageVector = outlineIcon(
        "Sliders",
        "M3,5 H6 M10,5 H17 M3,15 H10 M14,15 H17 M10,5 A2,2 0,1 1,6,5 A2,2 0,1 1,10,5 Z M14,15 A2,2 0,1 " +
            "1,10,15 A2,2 0,1 1,14,15 Z",
    )
    public val FolderOpen: ImageVector = outlineIcon(
        "FolderOpen",
        "M2.5,8 V5 Q2.5,3.5 4,3.5 H7 L9,5.5 H15 Q16.5,5.5 16.5,7 V8 M2.5,8 H18 L15.5,16 H4 Z",
    )
    public val FolderPlus: ImageVector = outlineIcon(
        "FolderPlus",
        "M2.5,7 V5 Q2.5,3.5 4,3.5 H7 L9,5.5 H16 Q17.5,5.5 17.5,7 V15 Q17.5,16.5 16,16.5 H4 Q2.5,16.5 " +
            "2.5,15 Z M7,11 H13 M10,8 V14",
    )
    public val File: ImageVector = outlineIcon(
        "File",
        "M5,2.5 H11 L16,7.5 V16 Q16,17.5 14.5,17.5 H5 Q3.5,17.5 3.5,16 V4 Q3.5,2.5 5,2.5 Z M11,2.5 " +
            "V7.5 H16",
    )
    public val FileText: ImageVector = outlineIcon(
        "FileText",
        "M5,2.5 H11 L16,7.5 V16 Q16,17.5 14.5,17.5 H5 Q3.5,17.5 3.5,16 V4 Q3.5,2.5 5,2.5 Z M11,2.5 " +
            "V7.5 H16 M7,11 H12.5 M7,14 H11",
    )
    public val FileCode: ImageVector = outlineIcon(
        "FileCode",
        "M5,2.5 H11 L16,7.5 V16 Q16,17.5 14.5,17.5 H5 Q3.5,17.5 3.5,16 V4 Q3.5,2.5 5,2.5 Z M11,2.5 " +
            "V7.5 H16 M8,10 L6,12 L8,14 M12,10 L14,12 L12,14",
    )
    public val FilePlus: ImageVector = outlineIcon(
        "FilePlus",
        "M5,2.5 H11 L16,7.5 V16 Q16,17.5 14.5,17.5 H5 Q3.5,17.5 3.5,16 V4 Q3.5,2.5 5,2.5 Z M11,2.5 " +
            "V7.5 H16 M7,12 H13 M10,9 V15",
    )
    public val Image: ImageVector = outlineIcon(
        "Image",
        "M4,3 H16 Q17.5,3 17.5,4.5 V15.5 Q17.5,17 16,17 H4 Q2.5,17 2.5,15.5 V4.5 Q2.5,3 4,3 Z M3,14 " +
            "L7,10 L11,14 L14,11 L17,14 M13,7 A1,1 0,1 1,11,7 A1,1 0,1 1,13,7 Z",
    )
    public val Archive: ImageVector = outlineIcon("Archive", "M2.5,3 H17.5 V7 H2.5 Z M4,7 V17 H16 V7 M8,10 H12")
    public val Bookmark: ImageVector = outlineIcon("Bookmark", "M5,3 H15 V18 L10,14.5 L5,18 Z")
    public val Tag: ImageVector = outlineIcon(
        "Tag",
        "M3,3 H10 L18,11 L11,18 L3,10 Z M7,6 A1,1 0,1 1,5,6 A1,1 0,1 1,7,6 Z",
    )
    public val Calendar: ImageVector = outlineIcon(
        "Calendar",
        "M4,4 H16 Q17.5,4 17.5,5.5 V16 Q17.5,17.5 16,17.5 H4 Q2.5,17.5 2.5,16 V5.5 Q2.5,4 4,4 Z M6,2 " +
            "V6 M14,2 V6 M2.5,8 H17.5 M6,11 H7 M10,11 H11 M6,14 H7 M10,14 H11",
    )
    public val Chats: ImageVector = outlineIcon(
        "Chats",
        "M6,3 H16 Q17,3 17,4 V11 Q17,12 16,12 H14 M3,7 H12 Q13,7 13,8 V14 Q13,15 12,15 H6 L3,18 V15 " +
            "Q2,15 2,14 V8 Q2,7 3,7 Z",
    )
    public val Send: ImageVector = outlineIcon("Send", "M2.5,3 L18,10 L2.5,17 L5.5,10 Z M5.5,10 H18", autoMirror = true)
    public val Reply: ImageVector = outlineIcon("Reply", "M8,4 L3,9 L8,14 M3,9 H10 Q17,9 17,16 V13", autoMirror = true)
    public val Mail: ImageVector = outlineIcon(
        "Mail",
        "M4,4 H16 Q17.5,4 17.5,5.5 V14.5 Q17.5,16 16,16 H4 Q2.5,16 2.5,14.5 V5.5 Q2.5,4 4,4 Z M3,5 " +
            "L10,10.5 L17,5",
    )
    public val Inbox: ImageVector = outlineIcon(
        "Inbox",
        "M5,3 H15 L18,11 V17 H2 V11 Z M2,11 H7 L8,13 H12 L13,11 H18",
    )
    public val Bell: ImageVector = outlineIcon(
        "Bell",
        "M4,14 L5.5,11 V7 A4.5,4.5 0,0 1,14.5,7 V11 L16,14 Z M8,17 Q10,19 12,17 M10,1.5 V2.5",
    )
    public val BellOff: ImageVector = outlineIcon(
        "BellOff",
        "M3,3 L17,17 M5.5,6 V11 L4,14 H13 M14.5,11 V7 A4.5,4.5 0,0 0,8.5,2.8 M8,17 Q10,19 12,17",
    )
    public val User: ImageVector = outlineIcon(
        "User",
        "M13.5,6 A3.5,3.5 0,1 1,6.5,6 A3.5,3.5 0,1 1,13.5,6 Z M3.5,17 V15 Q3.5,11.5 10,11.5 Q16.5,11.5 " +
            "16.5,15 V17",
    )
    public val Users: ImageVector = outlineIcon(
        "Users",
        "M11,6 A3,3 0,1 1,5,6 A3,3 0,1 1,11,6 Z M2,17 V15 Q2,12 8,12 Q14,12 14,15 V17 M14,3 A3,3 0,0 " +
            "1,14,9 M16,12 Q18,13 18,15 V17",
    )
    public val Mic: ImageVector = outlineIcon(
        "Mic",
        "M7,5 A3,3 0,0 1,13,5 V10 A3,3 0,0 1,7,10 Z M4,9 V10 A6,6 0,0 0,16,10 V9 M10,16 V19 M7,19 H13",
    )
    public val MicOff: ImageVector = outlineIcon(
        "MicOff",
        "M3,3 L17,17 M7,7 V10 A3,3 0,0 0,11,12.8 M9,2.2 Q13,1 13,5 V9 M4,9 V10 A6,6 0,0 0,13,15.2 " +
            "M16,9 V10 Q16,11 15.5,12 M10,16 V19 M7,19 H13",
    )
    public val Paperclip: ImageVector = outlineIcon(
        "Paperclip",
        "M7,11 L12,6 Q14,4 15.5,5.5 Q17,7 15,9 L8,16 Q5,19 2.5,16.5 Q0.5,14 3,11.5 L11,3.5 Q14,0.5 " +
            "17,3.5",
    )
    public val Video: ImageVector = outlineIcon(
        "Video",
        "M3,5 H11 Q12,5 12,6 V14 Q12,15 11,15 H3 Q2,15 2,14 V6 Q2,5 3,5 Z M12,8 L18,5 V15 L12,12",
    )
    public val Info: ImageVector = outlineIcon(
        "Info",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M10,9 V14 M10,6 V6.1",
    )
    public val Warning: ImageVector = outlineIcon(
        "Warning",
        "M8.5,3 Q10,1 11.5,3 L18,15 Q19,17 17,17 H3 Q1,17 2,15 Z M10,7 V11 M10,14 V14.1",
    )
    public val Help: ImageVector = outlineIcon(
        "Help",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M7.5,7 Q7.5,4.5 10,4.5 Q12.5,4.5 " +
            "12.5,7 Q12.5,8.5 10,10 V11 M10,14 V14.1",
    )
    public val Success: ImageVector = outlineIcon(
        "Success",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M6,10 L9,13 L14,7",
    )
    public val Error: ImageVector = outlineIcon(
        "Error",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M7,7 L13,13 M13,7 L7,13",
    )
    public val Circle: ImageVector = outlineIcon(
        "Circle",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z",
    )
    public val Loading: ImageVector = outlineIcon("Loading", "M10,2.5 A7.5,7.5 0,1 1,2.5,10")
    public val Lock: ImageVector = outlineIcon(
        "Lock",
        "M6,8 V6 A4,4 0,0 1,14,6 V8 M4,8 H16 V17.5 H4 Z M10,12 V14",
    )
    public val Unlock: ImageVector = outlineIcon(
        "Unlock",
        "M6,8 V6 A4,4 0,0 1,13.5,4 M4,8 H16 V17.5 H4 Z M10,12 V14",
    )
    public val Shield: ImageVector = outlineIcon(
        "Shield",
        "M10,2 L17,5 V10 Q17,15 10,18 Q3,15 3,10 V5 Z M7,10 L9,12 L13,7",
    )
    public val Eye: ImageVector = outlineIcon(
        "Eye",
        "M2,10 Q10,-1 18,10 Q10,21 2,10 Z M13,10 A3,3 0,1 1,7,10 A3,3 0,1 1,13,10 Z",
    )
    public val EyeOff: ImageVector = outlineIcon(
        "EyeOff",
        "M3,3 L17,17 M7,5 Q13,2 18,10 Q16,13 14,14 M11,15 Q6,16 2,10 Q3,8 5,6 M7.5,8 Q6,12 10,13",
    )
    public val Wifi: ImageVector = outlineIcon(
        "Wifi",
        "M2,6 Q10,-0.5 18,6 M5,9 Q10,5 15,9 M8,12 Q10,10.5 12,12 M10,15 V15.1",
    )
    public val WifiOff: ImageVector = outlineIcon(
        "WifiOff",
        "M3,3 L17,17 M8,3.5 Q13,2 18,6 M2,6 L4,4.5 M5,9 Q7,7 8,7.5 M8,12 Q10,10.5 12,12 M10,15 V15.1",
    )
    public val Sun: ImageVector = outlineIcon(
        "Sun",
        "M14,10 A4,4 0,1 1,6,10 A4,4 0,1 1,14,10 Z M10,1 V3 M10,17 V19 M1,10 H3 M17,10 H19 M3.5,3.5 " +
            "L5,5 M15,15 L16.5,16.5 M3.5,16.5 L5,15 M15,5 L16.5,3.5",
    )
    public val Moon: ImageVector = outlineIcon("Moon", "M17,12 A7.5,7.5 0,1 1,8,3 A6,6 0,0 0,17,12 Z")
    public val Monitor: ImageVector = outlineIcon(
        "Monitor",
        "M3,3 H17 Q18,3 18,4 V12 Q18,13 17,13 H3 Q2,13 2,12 V4 Q2,3 3,3 Z M10,13 V17 M6,17 H14",
    )
    public val Phone: ImageVector = outlineIcon(
        "Phone",
        "M7,2 H13 Q15,2 15,4 V16 Q15,18 13,18 H7 Q5,18 5,16 V4 Q5,2 7,2 Z M8,4 H12 M9,15.5 H11",
    )
    public val Terminal: ImageVector = outlineIcon(
        "Terminal",
        "M4,3 H16 Q18,3 18,5 V15 Q18,17 16,17 H4 Q2,17 2,15 V5 Q2,3 4,3 Z M5,7 L8,10 L5,13 M11,13 H15",
    )
    public val Code: ImageVector = outlineIcon("Code", "M6,5 L1.5,10 L6,15 M14,5 L18.5,10 L14,15 M12,3 L8,17")
    public val Commit: ImageVector = outlineIcon(
        "Commit",
        "M13.5,10 A3.5,3.5 0,1 1,6.5,10 A3.5,3.5 0,1 1,13.5,10 Z M2,10 H6.5 M13.5,10 H18",
    )
    public val Merge: ImageVector = outlineIcon(
        "Merge",
        "M7,4 A2,2 0,1 1,3,4 A2,2 0,1 1,7,4 Z M7,16 A2,2 0,1 1,3,16 A2,2 0,1 1,7,16 Z M17,5 A2,2 0,1 " +
            "1,13,5 A2,2 0,1 1,17,5 Z M5,6 V14 M15,7 Q15,11 5,11",
    )
    public val PullRequest: ImageVector = outlineIcon(
        "PullRequest",
        "M7,4 A2,2 0,1 1,3,4 A2,2 0,1 1,7,4 Z M7,16 A2,2 0,1 1,3,16 A2,2 0,1 1,7,16 Z M17,16 A2,2 0,1 " +
            "1,13,16 A2,2 0,1 1,17,16 Z M5,6 V14 M15,14 V8 Q15,5 12,5 H10 M12,3 L10,5 L12,7",
    )
    public val Cloud: ImageVector = outlineIcon(
        "Cloud",
        "M6,15.5 C1,15.5 1,8 5.5,7.5 C6,1 14,1 15,6.5 C19.5,6.5 19.5,15.5 15,15.5 Z",
    )
    public val CloudOff: ImageVector = outlineIcon(
        "CloudOff",
        "M3,3 L17,17 M5.5,7.5 C1,8 1,15.5 6,15.5 H12 M8,3.5 Q14,1 15,6.5 " +
            "C18.5,6.5 19.5,10.5 17.5,13",
    )
    public val Database: ImageVector = outlineIcon(
        "Database",
        "M17,5 A7,2.5 0,1 1,3,5 A7,2.5 0,1 1,17,5 Z M3,5 V15 A7,2.5 0,0 0,17,15 V5 M3,10 A7,2.5 0,0 " +
            "0,17,10",
    )
    public val Globe: ImageVector = outlineIcon(
        "Globe",
        "M17.5,10 A7.5,7.5 0,1 1,2.5,10 A7.5,7.5 0,1 1,17.5,10 Z M10,2.5 C5,6 5,14 10,17.5 C15,14 15,6 " +
            "10,2.5 M2.5,10 H17.5",
    )
    public val Play: ImageVector = outlineIcon("Play", "M6,3.5 L17,10 L6,16.5 Z")
    public val Pause: ImageVector = outlineIcon("Pause", "M6,4 V16 M14,4 V16")
    public val Volume: ImageVector = outlineIcon(
        "Volume",
        "M3,7 H6 L11,3 V17 L6,13 H3 Z M14,7 Q17,10 14,13 M16,4 Q21,10 16,16",
    )
    public val VolumeOff: ImageVector = outlineIcon(
        "VolumeOff",
        "M3,7 H6 L10,3 V17 L6,13 H3 Z M14,7 L18,13 M18,7 L14,13",
    )
    public val Headphones: ImageVector = outlineIcon(
        "Headphones",
        "M3,12 V10 A7,7 0,0 1,17,10 V12 M3,11 H6 V17 H3 Z M14,11 H17 V17 H14 Z",
    )
    public val Camera: ImageVector = outlineIcon(
        "Camera",
        "M3,6 H6 L7,3 H13 L14,6 H17 Q18,6 18,7 V16 Q18,17 17,17 H3 Q2,17 2,16 V7 Q2,6 3,6 Z M14,11 " +
            "A4,4 0,1 1,6,11 A4,4 0,1 1,14,11 Z",
    )
    public val Heart: ImageVector = outlineIcon("Heart", "M10,17 L3,10 C-2,4 5,-0.5 10,5 C15,-0.5 22,4 17,10 Z")
    public val Star: ImageVector = outlineIcon(
        "Star",
        "M10,2 L12.5,7 L18,8 L14,12 L15,18 L10,15 L5,18 L6,12 L2,8 L7.5,7 Z",
    )

    /** Complete, stable inventory for asset pickers and tooling. */
    public val All: ImmutableList<ImageVector> = persistentListOf(
        Home, HomeFilled, History, ArrowLeft, ArrowRight, ArrowUp, ArrowDown, ChevronLeft, ChevronRight,
        ChevronUp, ChevronDown, Sidebar, Grid, Menu, ExternalLink, Maximize, Minimize,
        Plus, Minus, Close, Check, Search, Edit, Copy, Clipboard, Trash, Download, Upload, Refresh, Undo,
        Redo, Filter, Sort, Share, Link, Pin, Unpin, More, MoreVertical, Settings, Sliders,
        Folder, FolderOpen, FolderPlus, File, FileText, FileCode, FilePlus, Library, Images, Image,
        Archive, Bookmark, Tag, Calendar,
        Chat, Chats, Send, Reply, Mail, Inbox, Bell, BellOff, User, Users, Mic, MicOff, Paperclip, Video,
        Info, Alert, Warning, Help, Success, Error, Circle, Loading, Lock, Unlock, Shield, Eye, EyeOff,
        Wifi, WifiOff, Sun, Moon,
        Laptop, Monitor, Phone, Terminal, Code, Branch, Commit, Merge, PullRequest, Cloud, CloudOff,
        Database, Globe, Sparkles, Plan,
        Play, Pause, Stop, Volume, VolumeOff, Headphones, Camera, Heart, Star,
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
