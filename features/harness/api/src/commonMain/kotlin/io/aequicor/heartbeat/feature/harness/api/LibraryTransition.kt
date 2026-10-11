package io.aequicor.heartbeat.feature.harness.api

internal data class LibraryTransition(
    val state: HarnessState.Ready,
    val effects: List<HarnessEffect> = emptyList(),
    val output: HarnessOutput? = null,
)
