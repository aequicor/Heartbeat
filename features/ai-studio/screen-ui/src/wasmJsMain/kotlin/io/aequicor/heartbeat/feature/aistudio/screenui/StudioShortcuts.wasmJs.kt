package io.aequicor.heartbeat.feature.aistudio.screenui

/** The browser reports the host OS in its platform string; macOS uses ⌘ for studio shortcuts. */
internal actual fun isStudioMetaShortcut(): Boolean = navigatorPlatform().startsWith("Mac", ignoreCase = true)

@JsFun("() => (globalThis.navigator && (navigator.userAgentData?.platform || navigator.platform)) || ''")
private external fun navigatorPlatform(): String
