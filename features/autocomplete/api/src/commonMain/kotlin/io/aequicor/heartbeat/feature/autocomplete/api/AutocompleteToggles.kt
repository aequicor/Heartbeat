package io.aequicor.heartbeat.feature.autocomplete.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Composer hints for `/` commands, `@` skills and `@` project files; off hides the suggestion popup. */
public val AutocompleteEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    key = "autocomplete.enabled",
    description = "Композер: подсказки /команд, @скиллов и @файлов",
    default = false,
)
