package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

/** Shared native-call classification for desktop adapters; paths come from trusted adapter context. */
public interface NativeCallClassifier {
    /**
     * Whether a command can terminate the application or its parent processes. This bounded heuristic is not a
     * shell sandbox; an unrecognised command still requires the adapter's ordinary trust rules.
     */
    public fun terminatesHost(command: String): Boolean

    /**
     * Whether the pinned absolute path resolves inside the real workspace and outside .git. Rewritten, relative,
     * inaccessible and ambiguous paths are not covered. Resolves links on an IO dispatcher.
     */
    public suspend fun isWorkspaceEdit(path: String, workspace: String): Boolean
}
