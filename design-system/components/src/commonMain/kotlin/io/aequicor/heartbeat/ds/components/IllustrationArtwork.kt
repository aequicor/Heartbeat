package io.aequicor.heartbeat.ds.components

/** Original layered scenes in a 240 by 180 viewport, independent of rendering and theme. */
internal data class IllustrationArtwork(
    val back: String,
    val paper: String,
    val accent: String,
    val detail: String,
    val accentDetail: String = "",
)

internal fun HbIllustrationKind.artwork(): IllustrationArtwork = when (this) {
    HbIllustrationKind.Welcome -> WelcomeArtwork
    HbIllustrationKind.EmptyWorkspace -> WorkspaceArtwork
    HbIllustrationKind.EmptyChat -> ChatArtwork
    HbIllustrationKind.NoResults -> SearchArtwork
    HbIllustrationKind.Success -> SuccessArtwork
    HbIllustrationKind.Error -> ErrorArtwork
    HbIllustrationKind.Offline -> OfflineArtwork
    HbIllustrationKind.Upload -> UploadArtwork
}

private val WelcomeArtwork = IllustrationArtwork(
    back = "M63,53 L165,40 L180,137 L78,149 Z",
    paper = "M65,62 Q65,54 73,54 H161 Q169,54 169,62 V135 Q169,143 161,143 H73 Q65,143 65,135 Z",
    accent = "M153,30 Q155,51 175,53 Q155,55 153,76 Q151,55 131,53 Q151,51 153,30 Z " +
        "M88,79 H116 Q121,79 121,84 V98 Q121,103 116,103 H88 Q83,103 83,98 V84 Q83,79 88,79 Z",
    detail = "M65,68 H127 M79,61 H81 M86,61 H88 M93,61 H95 M83,117 H150 M83,127 H128 " +
        "M135,86 H153 M135,97 H145 M185,78 V88 M180,83 H190",
    accentDetail = "M93,91 L98,96 L111,85",
)

private val WorkspaceArtwork = IllustrationArtwork(
    back = "M61,73 V58 Q61,52 67,52 H103 L117,65 H173 Q179,65 179,71 V135 H61 Z",
    paper = "M83,43 H132 L151,62 V121 H83 Z M132,43 V62 H151",
    accent = "M55,83 Q54,77 61,77 H178 Q185,77 184,84 L176,140 Q175,146 168,146 H72 " +
        "Q65,146 64,140 Z",
    detail = "M91,58 H117 M96,69 H123 M174,39 V49 M169,44 H179",
    accentDetail = "M95,77 V84 M110,77 V84 M87,107 H151 M87,119 H131",
)

private val ChatArtwork = IllustrationArtwork(
    back = "M101,77 H175 Q183,77 183,85 V126 Q183,134 175,134 H162 L163,148 L145,134 " +
        "H101 Q93,134 93,126 V85 Q93,77 101,77 Z",
    paper = "M61,47 H144 Q153,47 153,56 V105 Q153,114 144,114 H89 L69,129 L71,114 " +
        "H61 Q52,114 52,105 V56 Q52,47 61,47 Z",
    accent = "M175,37 Q176,50 189,51 Q176,52 175,65 Q174,52 161,51 Q174,50 175,37 Z",
    detail = "M73,73 H133 M73,86 H121 M73,99 H104 M118,122 H163 M42,132 H48 M45,129 V135",
)

private val SearchArtwork = IllustrationArtwork(
    back = "M67,55 L148,42 L164,136 L83,149 Z",
    paper = "M71,46 H143 Q149,46 149,52 V135 Q149,141 143,141 H71 Q65,141 65,135 V52 Q65,46 71,46 Z",
    accent = "M162,91 A28,28 0,1 1,106,91 A28,28 0,1 1,162,91 Z",
    detail = "M79,65 H107 M79,79 H94 M79,110 H93 M79,124 H118 " +
        "M154,112 L180,138 Q184,144 177,146 L150,118 M177,59 H187 M182,54 V64",
    accentDetail = "M151,91 A17,17 0,1 1,117,91 A17,17 0,1 1,151,91 Z M128,91 H140",
)

private val SuccessArtwork = IllustrationArtwork(
    back = "M72,43 L151,52 L141,146 L62,137 Z",
    paper = "M80,38 H143 Q151,38 151,46 V134 Q151,142 143,142 H80 Q72,142 72,134 V46 Q72,38 80,38 Z",
    accent = "M188,116 A30,30 0,1 1,128,116 A30,30 0,1 1,188,116 Z",
    detail = "M88,59 H135 M88,73 H124 M88,97 H111 M88,110 H106 M49,77 L55,83 " +
        "M172,51 L180,44 M187,76 H195 M57,118 V126",
    accentDetail = "M143,116 L153,126 L173,106",
)

private val ErrorArtwork = IllustrationArtwork(
    back = "M70,50 L157,43 L165,138 L78,145 Z",
    paper = "M66,52 H152 Q159,52 159,59 V133 Q159,140 152,140 H66 Q59,140 59,133 V59 Q59,52 66,52 Z",
    accent = "M160,87 Q164,80 168,87 L195,133 Q199,141 191,141 H137 Q129,141 133,133 Z",
    detail = "M59,72 H159 M72,62 H74 M81,62 H83 M90,62 H92 M77,89 H130 M77,103 H118 " +
        "M77,119 H101 M174,50 L181,43 M186,65 H196",
    accentDetail = "M164,102 V118 M164,128 V129",
)

private val OfflineArtwork = IllustrationArtwork(
    back = "M76,135 H169 Q181,135 181,145 H64 Q64,135 76,135 Z",
    paper = "M70,61 H169 Q177,61 177,69 V127 H62 V69 Q62,61 70,61 Z " +
        "M55,127 H184 L178,140 H61 Z",
    accent = "M76,73 H163 V116 H76 Z",
    detail = "M108,127 V131 H131 V127 M100,41 Q119,27 138,41 M107,50 Q119,41 131,50 " +
        "M92,29 L146,61",
    accentDetail = "M107,89 L132,105 M132,89 L107,105",
)

private val UploadArtwork = IllustrationArtwork(
    back = "M66,67 Q66,60 73,60 H169 Q176,60 176,67 V131 H66 Z",
    paper = "M80,84 V44 Q80,38 86,38 H123 L143,58 V107 H80 Z M123,38 V58 H143",
    accent = "M57,88 H94 L103,104 H142 L151,88 H185 V136 Q185,143 178,143 H64 Q57,143 57,136 Z",
    detail = "M112,87 V61 M101,72 L112,61 L123,72 M177,37 V49 M171,43 H183",
    accentDetail = "M74,121 H99",
)
