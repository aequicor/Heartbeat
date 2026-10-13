package io.aequicor.heartbeat.feature.chromiumbridge.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Master switch of the Chromium bridge: the desktop socket the native-messaging host relays to, native host
 * registration for Chromium browsers, the settings section and the side-panel chat. While it is off the socket is
 * closed and removed, so a registered host only reports that Heartbeat is unavailable. Desktop only.
 */
public val ChromiumBridgeEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "chromium_bridge.enabled",
    "Расширение Chromium: чат Heartbeat в боковой панели браузера",
)

/**
 * Lets agents drive the user's external Chromium browser through the `ex_control_*` tools. Requires
 * [ChromiumBridgeEnabled]; the extension additionally keeps its own consent switch, off by default.
 */
public val ChromiumBridgeAgentControl: FeatureToggle.Flag = FeatureToggle.Flag(
    "chromium_bridge.agent_control",
    "Расширение Chromium: управление внешним браузером агентами (ex_control_*)",
)
