package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Preparation of an optional engine runtime on its first use. Observation never starts a download. Preparation
 * belongs to the requesting operation and remains cancellable before a native runtime is created; retrying that
 * operation retries failed preparation. It is independent of the optional engine-management panel.
 */
public sealed interface EnginePreparation {
    /** The runtime can be prepared when an operation first needs it. */
    public data object Idle : EnginePreparation

    /** Downloaded bytes of a pinned runtime; the publisher may omit its total size. */
    public data class Downloading(val bytes: Long, val total: Long?) : EnginePreparation

    /** Checking or extracting the verified runtime before publishing it. */
    public data object Installing : EnginePreparation

    /** No preparation is needed, or the runtime is ready. */
    public data object Ready : EnginePreparation

    /** Safe failure category; retry the original operation after addressing its cause. */
    public data class Failed(val reason: EnginePreparationFailure) : EnginePreparation
}

/** Safe first-use setup failures, without remote URLs, local paths or exception messages. */
public enum class EnginePreparationFailure { Network, Storage, InvalidRuntime, Unavailable }
