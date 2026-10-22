package io.aequicor.heartbeat.feature.aistudio.impl.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What a file preview shows; text is bounded by [StudioFileReader.MAX_PREVIEW_BYTES]. */
sealed interface StudioFileContent {
    /** UTF-8 text; malformed bytes are replaced. [modifiedAt] (epoch ms) tells a later read whether it changed. */
    data class Text(val text: String, val modifiedAt: Long) : StudioFileContent {
        override fun toString(): String = "StudioFileContent.Text(length=${text.length})"
    }

    /** Larger than the preview reads; [sizeBytes] is what was read, at least one byte over the limit. */
    data class TooLarge(val sizeBytes: Long) : StudioFileContent

    /** Contains NUL bytes, so it is not text. */
    data object Binary : StudioFileContent

    /** Missing, a directory, or unreadable. */
    data object Unavailable : StudioFileContent
}

/** Read-only local files for link previews; platforms without local files answer [isAvailable] false. */
interface StudioFileReader {
    val isAvailable: Boolean

    /**
     * The absolute normalized path of [path]: `~/` from the user's home, an absolute path as is, a relative one
     * against [base]. Null when relative without a [base], or not a valid path on this platform.
     */
    suspend fun resolve(path: String, base: String?): String?

    /** Reads the regular file at an absolute [path]. */
    suspend fun read(path: String): StudioFileContent

    /** Last modification (epoch ms) of the file at [path], or null when it is unavailable. */
    suspend fun modifiedAt(path: String): Long?

    /** Preview limits. */
    companion object {
        /** The largest file a preview reads. */
        const val MAX_PREVIEW_BYTES: Long = 1024L * 1024L
    }
}

/** File links of one chat: availability of the preview and resolution of a link against the chat's checkout. */
interface StudioFileLinks {
    /** The preview toggle on a platform with local files. */
    val availability: Flow<Boolean>

    /**
     * The absolute path a link of a chat points at. Relative links resolve against the checkout the chat's agent
     * works in ([sessionId]'s worktree or project, else [projectId] of a new chat); null when they cannot, as in a
     * sandbox chat, whose sources have no folder on the host.
     */
    suspend fun resolve(path: String, sessionId: String?, projectId: String?): String?

    /** The absolute path of a link inside a previewed file: relative ones resolve against its [directory]. */
    suspend fun resolveIn(path: String, directory: String): String?

    /** Reads a resolved file for the preview. */
    suspend fun read(path: String): StudioFileContent

    /** Last modification of a resolved file, polled while the preview is shown. */
    suspend fun modifiedAt(path: String): Long?

    /** No preview: demo workspaces and tests. */
    object None : StudioFileLinks {
        override val availability: Flow<Boolean> = flowOf(false)
        override suspend fun resolve(path: String, sessionId: String?, projectId: String?): String? = null
        override suspend fun resolveIn(path: String, directory: String): String? = null
        override suspend fun read(path: String): StudioFileContent = StudioFileContent.Unavailable
        override suspend fun modifiedAt(path: String): Long? = null
    }
}
