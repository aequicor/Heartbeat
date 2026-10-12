package io.aequicor.heartbeat.feature.serviceasui.api

/** Names of the feature's agent tools and the result format hosts read back from them. */
public object ServiceUiTools {
    /** Tool group in settings and in the tool policy. */
    public const val GROUP: String = "service_ui"

    /** Reference of the spec format. */
    public const val REFERENCE: String = "ui_reference"

    /** Surfaces of the library and owners with their methods. */
    public const val LIST: String = "ui_list"

    /** One surface with its spec. */
    public const val GET: String = "ui_get"

    /** Creates or replaces a surface. */
    public const val PUT: String = "ui_put"

    /** Deletes a surface. */
    public const val DELETE: String = "ui_delete"

    /** Shows a card in the agent's response. */
    public const val SHOW: String = "ui_show"

    /** Calls a method of an owner to check its result. */
    public const val CALL: String = "ui_call"

    /** Result text of a successful [SHOW] call that created the card [cardId]; [shownCardId] reads it back. */
    public fun shown(cardId: String): String {
        require(cardId.matches(CardId)) { "Invalid card id" }
        return "UI card $cardId shown. The user sees it in your reply; do not repeat its content."
    }

    /** The card created by a successful [SHOW] call, read from its [result] text; null for other tools and errors. */
    public fun shownCardId(toolName: String, result: String): String? =
        if (toolName == SHOW) Shown.find(result)?.groupValues?.get(1) else null
}

private val CardId = Regex("[a-z0-9_]{1,40}")
private val Shown = Regex("UI card ([a-z0-9_]{1,40}) shown\\.")
