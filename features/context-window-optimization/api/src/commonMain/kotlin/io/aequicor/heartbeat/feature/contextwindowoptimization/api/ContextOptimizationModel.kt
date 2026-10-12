package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import kotlinx.serialization.Serializable

/** How a compaction is preferably done; see [CompactionMethod]. */
@Serializable
public enum class CompactionStrategy {
    /** The engine summarizes its own context (`CompactsContext`). */
    Native,

    /** A new native session is seeded with the essence of the old one. */
    Rollover,

    /** Native first; rollover when native is unsupported or did not bring the context below the threshold. */
    Auto,
}

/** The way one compaction runs. */
@Serializable
public enum class CompactionMethod { Native, Rollover }

/** Who asked for a compaction. */
@Serializable
public enum class CompactionOrigin {
    /** The user, with `/compact` or the context indicator. */
    User,

    /** The threshold of the settings, between turns. */
    Auto,

    /** The agent itself, with a hosted tool; runs after its turn. */
    Agent,

    /** Another feature, for example a harness workflow. */
    Feature,
}

/** Result of one compaction reported by the host that ran it. */
@Serializable
public enum class CompactionResult {
    /** The context was replaced by its summary or continued in a new session. */
    Compacted,

    /** The attempt failed; the context is unchanged. */
    Failed,

    /** The method is not available for this session (engine or chat); the context is unchanged. */
    Unsupported,
}

/**
 * Profile preferences. [thresholdPercent] is the context occupancy that triggers automatic compaction;
 * the Koog limits shape the prompt of the Koog engine, [offloadThresholdChars] the longest tool output kept in
 * a prompt, and the collapse flags only the presentation of the chat feed.
 */
@Serializable
public data class ContextOptimizationSettings(
    val thresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT,
    val strategy: CompactionStrategy = CompactionStrategy.Auto,
    val isAutoCompactionEnabled: Boolean = true,
    val koogKeepRecentTurns: Int = DEFAULT_KEEP_RECENT_TURNS,
    val koogToolStubChars: Int = DEFAULT_TOOL_STUB_CHARS,
    val offloadThresholdChars: Int = DEFAULT_OFFLOAD_THRESHOLD_CHARS,
    val isReasoningCollapsed: Boolean = false,
    val isToolCallsCollapsed: Boolean = false,
) {
    init {
        require(thresholdPercent in THRESHOLD_RANGE) { "Threshold out of range" }
        require(koogKeepRecentTurns in KEEP_RECENT_TURNS_RANGE) { "Kept turns out of range" }
        require(koogToolStubChars in TOOL_STUB_CHARS_RANGE) { "Stub size out of range" }
        require(offloadThresholdChars in OFFLOAD_THRESHOLD_RANGE) { "Offload threshold out of range" }
    }

    /** Limits of the settings, shared with the settings screen. */
    public companion object {
        /** Default occupancy that triggers automatic compaction. */
        public const val DEFAULT_THRESHOLD_PERCENT: Int = 80

        /** Default number of the newest Koog turns whose tool output stays verbatim. */
        public const val DEFAULT_KEEP_RECENT_TURNS: Int = 4

        /** Default size of a stub that replaces older Koog tool output. */
        public const val DEFAULT_TOOL_STUB_CHARS: Int = 200

        /** Default longest tool output kept in a prompt. */
        public const val DEFAULT_OFFLOAD_THRESHOLD_CHARS: Int = 16_000

        /** Allowed thresholds, percent. */
        public val THRESHOLD_RANGE: IntRange = 50..95

        /** Allowed numbers of verbatim Koog turns. */
        public val KEEP_RECENT_TURNS_RANGE: IntRange = 1..20

        /** Allowed stub sizes. */
        public val TOOL_STUB_CHARS_RANGE: IntRange = 0..2_000

        /** Allowed offload thresholds. */
        public val OFFLOAD_THRESHOLD_RANGE: IntRange = 2_000..200_000
    }
}

/** A compaction waiting for its session to become idle. [instructions] are user data and never logged. */
public data class PendingCompaction(
    val method: CompactionMethod,
    val origin: CompactionOrigin,
    val instructions: String? = null,
) {
    init {
        require(instructions == null || instructions.isNotBlank())
    }

    override fun toString(): String =
        "PendingCompaction(method=$method, origin=$origin, instructions=${instructions?.length ?: 0})"
}

/**
 * Automatic attempts of one session since its context was last below the threshold minus the hysteresis.
 * [isExhausted] stops automatic compaction until the context drops below that level.
 */
public data class CompactionMark(val attempts: Int = 0, val isExhausted: Boolean = false)

/** Methods a session can run now: the engine declares `CompactsContext`; the chat allows a new session. */
public data class CompactionSupport(val isNative: Boolean, val isRollover: Boolean)
