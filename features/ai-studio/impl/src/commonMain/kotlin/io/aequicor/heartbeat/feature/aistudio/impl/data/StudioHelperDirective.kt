package io.aequicor.heartbeat.feature.aistudio.impl.data

internal const val HELPER_DIRECTIVE: String =
    "You are a helper working on one assigned step. Return the result to your caller. " +
        "Do not call scheduler_sleep: the host owns waiting, cancellation and recovery for this helper."
