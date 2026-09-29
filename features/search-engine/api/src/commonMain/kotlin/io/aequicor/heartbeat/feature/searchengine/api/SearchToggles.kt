package io.aequicor.heartbeat.feature.searchengine.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Exposes `web_search` / `web_fetch` to AI engines, starts the local search bridge and shows profile settings.
 * Engines with built-in web tools also get them: Claude `WebSearch` / `WebFetch`, Codex hosted `web_search`.
 * While off, every engine keeps its tool-less behaviour: Claude `--tools=`, Codex without dynamic tools and
 * experimental API, Koog without tool capability, Pi without the search extension.
 */
public val SearchEngineTools: FeatureToggle.Flag = FeatureToggle.Flag(
    "search.engine_tools",
    "Веб-поиск и чтение страниц как инструменты ИИ-движков",
)
