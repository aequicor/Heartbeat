package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Master switch of the feature: the panel route, the profile machine, the desktop capture and input hosts and
 * the agent tools. While it is off, every consumer sees [ComputerUseBlocker.UnsupportedPlatform] and no frame is
 * ever captured. Desktop only; mobile platforms stay unavailable even when it is on.
 */
public val ComputerUseEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "computer_use.enabled",
    "Управление компьютером: захват экрана и ввод для тестирования приложений",
)

/**
 * Capture of a single window instead of the whole desktop. Requires window enumeration and window capture on
 * the host: Windows uses `PrintWindow`, other platforms report [ComputerUseBlocker.UnsupportedPlatform] for
 * this mode while the desktop mode keeps working.
 */
public val ComputerUseWindowMode: FeatureToggle.Flag = FeatureToggle.Flag(
    "computer_use.window_mode",
    "Захват отдельного окна приложения",
)

/**
 * Mouse and keyboard input while the whole desktop is captured. Window capture is not affected: its input is
 * confined to the captured window. Off by default because desktop-wide input can reach any application.
 */
public val ComputerUseDesktopInput: FeatureToggle.Flag = FeatureToggle.Flag(
    "computer_use.desktop_input",
    "Ввод мыши и клавиатуры в режиме полного захвата рабочего стола",
)

/**
 * Publishes the `computer_*` hosted tools to AI engines through the profile dispatcher. Input tools still pass
 * the single trust gate; capture tools are read-only.
 */
public val ComputerUseAgentTools: FeatureToggle.Flag = FeatureToggle.Flag(
    "computer_use.agent_tools",
    "Инструменты computer_* для ИИ-движков",
)

/**
 * Prefers an engine's own [NativeComputerControl] over the host implementation. While it is off, the host
 * captures and injects input itself, so behaviour is identical for every engine.
 */
public val ComputerUseNativeRouting: FeatureToggle.Flag = FeatureToggle.Flag(
    "computer_use.native_routing",
    "Предпочитать собственное computer-use движка хостовому захвату",
)
