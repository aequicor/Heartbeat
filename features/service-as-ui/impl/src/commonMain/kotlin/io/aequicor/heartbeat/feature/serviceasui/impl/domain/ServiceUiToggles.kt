package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Web documents of agent interfaces (desktop): `web` nodes draw their HTML in an isolated browser document. */
internal val ServiceUiWeb: FeatureToggle.Flag = FeatureToggle.Flag(
    "service_ui.web",
    "Веб-документы в интерфейсах агента (Desktop)",
    default = true,
)
