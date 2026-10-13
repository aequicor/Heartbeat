package io.aequicor.heartbeat.feature.chromiumbridge.protocol

/**
 * Error codes of a bridge response (`res.error.code`), shared by the app, the extension service worker
 * (`ErrorCode` in `chromium-plugin/src/shared/protocol.js`) and the side panel, which maps them to user-facing texts.
 */
public object BridgeErrorCode {
    public const val BAD_REQUEST: String = "bad_request"
    public const val UNKNOWN_METHOD: String = "unknown_method"
    public const val UNSUPPORTED_PROTOCOL: String = "unsupported_protocol"
    public const val NOT_READY: String = "not_ready"
    public const val UNAVAILABLE: String = "unavailable"
    public const val BUSY: String = "busy"
    public const val UNKNOWN_CHAT: String = "unknown_chat"
    public const val NOT_CONTINUABLE: String = "not_continuable"
    public const val NO_MODEL: String = "no_model"
    public const val STALE_REQUEST: String = "stale_request"
    public const val TOO_LARGE: String = "too_large"
    public const val FORBIDDEN: String = "forbidden"
    public const val NO_SUCH_TAB: String = "no_such_tab"
    public const val TIMEOUT: String = "timeout"
    public const val INTERNAL: String = "internal"
}
