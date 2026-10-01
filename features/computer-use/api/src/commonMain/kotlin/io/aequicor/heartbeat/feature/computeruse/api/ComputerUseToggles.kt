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
 * Prefers an engine's own [NativeComputerControl] over the host implementation. While it is off, the host
 * captures and injects input itself, so behaviour is identical for every engine.
 */
public val ComputerUseNativeRouting: FeatureToggle.Flag = FeatureToggle.Flag(
    "computer_use.native_routing",
    "Предпочитать собственное computer-use движка хостовому захвату",
)
