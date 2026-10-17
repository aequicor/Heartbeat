package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/** Web documents of agent interfaces (desktop): `web` nodes draw their HTML in an isolated browser document. */
internal val ServiceUiWeb: FeatureToggle.Flag = FeatureToggle.Flag(
    "service_ui.web",
    "Веб-документы в интерфейсах агента (Desktop)",
    default = true,
)

/** Persisted app-wide policy; changing it reloads pages, including active previews. */
internal val ServiceUiWebSafeMode: FeatureToggle.Flag = FeatureToggle.Flag(
    "service_ui.web_safe_mode",
    "Безопасный режим мини-апп (все профили). Выкл.: внешние CSS, шрифты, изображения и скрипты. " +
        "Скрипты видят данные и разрешённые методы владельца; запросы могут отправлять данные. " +
        "Переключение перезагружает мини-аппы: несохранённый ввод может сброситься. " +
        "Автоматическое ревью не гарантирует безопасность внешнего кода.",
    default = true,
)
