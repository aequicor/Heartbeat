package io.aequicor.heartbeat.feature.harness.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Shared library and runtime quotas. UTF-8 storage limits include serialized metadata and code. */
public object HarnessLimits {
    public const val HARNESSES: Int = 32
    public const val ITEMS: Int = 32
    public const val BYTES_PER_HARNESS: Int = 256 * 1024
    public const val BYTES_PER_PROFILE: Int = 2 * 1024 * 1024
    public const val ATTACHED_PER_HARNESS: Int = 64
    public const val ACTIVE_PER_SESSION: Int = 8
    public const val SCRIPT_TOOLS: Int = 8
    public const val TIMERS: Int = 8
    public val MIN_TIMER: Duration = 30.seconds
    public const val SENDS_PER_MINUTE: Int = 6
    public const val SEND_CHAIN: Int = 3
    public const val WAKES_PER_SESSION: Int = 2
    public const val WAKES_PER_PROFILE: Int = 16
    public const val RUNS: Int = 4
    public const val HELPERS_PER_RUN: Int = 4
    public const val STEPS: Int = 64
    public val RUN_TIME: Duration = 6.hours
    public const val FAILURES: Int = 5
    public const val DIAGNOSTICS: Int = 20
    public const val SKILL_CHARS: Int = 16 * 1024
    public const val INSTRUCTION_CHARS: Int = 2 * 1024
    public const val TEMPLATE_CHARS: Int = 8 * 1024
    public const val SOURCE_CHARS: Int = 32 * 1024
    public const val RESULT_CHARS: Int = 8192
}
