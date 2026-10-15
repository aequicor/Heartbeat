package io.aequicor.heartbeat.feature.serviceasui.api

/** Names of the feature's agent tools and the result format hosts read back from them. */
public object ServiceUiTools {
    /** Tool group in settings and in the tool policy. */
    public const val GROUP: String = "service_ui"

    /** Reference of the spec format. */
    public const val REFERENCE: String = "ui_reference"

    /** Renders an isolated WebView draft into an image and layout diagnostics without owner calls or persistence. */
    public const val PREVIEW: String = "ui_preview"

    /** Surfaces of the library and owners with their methods. */
    public const val LIST: String = "ui_list"

    /** One surface with its spec. */
    public const val GET: String = "ui_get"

    /** Creates or replaces a surface. */
    public const val PUT: String = "ui_put"

    /** Deletes a surface. */
    public const val DELETE: String = "ui_delete"

    /** Shows or replaces the chat's live library app, or adds an anonymous inline snapshot. */
    public const val SHOW: String = "ui_show"

    /** Calls a method of an owner to check its result. */
    public const val CALL: String = "ui_call"

    /** Successful [SHOW] receipt. Live library apps reuse a chat slot; anonymous cards remain snapshots. */
    public fun shown(cardId: String, isLive: Boolean = false): String {
        require(cardId.matches(CardId)) { "Invalid card id" }
        val kind = if (isLive) "app" else "card"
        return "UI $kind $cardId shown. The user sees it in your reply; do not repeat its content."
    }

    /**
     * The card named by a successful [SHOW] call, read from its whole [result] text as [shown] writes it; null for
     * other tools, errors and any other text.
     */
    public fun shownCardId(toolName: String, result: String): String? =
        if (toolName == SHOW) Shown.matchEntire(result.trim())?.groupValues?.get(2) else null

    /** Whether a complete successful host receipt names the single live app rather than an inline snapshot. */
    public fun isLiveCard(toolName: String, result: String): Boolean =
        toolName == SHOW && Shown.matchEntire(result.trim())?.groupValues?.get(1) == "app"
}

private val CardId = Regex("[a-z0-9_]{1,40}")
private val Shown = Regex(
    "UI (card|app) ([a-z0-9_]{1,40}) shown\\. The user sees it in your reply; do not repeat its content\\.",
)
