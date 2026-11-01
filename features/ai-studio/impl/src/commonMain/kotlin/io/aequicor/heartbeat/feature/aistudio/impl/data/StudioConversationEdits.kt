package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.SessionEdit
import kotlinx.coroutines.sync.withLock

/** Direct user metadata changes require a full chat both before releasing a handle and before the durable write. */
internal suspend fun StudioChatRecords.editConversation(
    id: String,
    edit: SessionEdit,
    isRunning: Boolean,
    release: suspend () -> Unit,
) {
    record(id).requireHumanConversation()
    if (edit is SessionEdit.SetArchived && edit.isArchived && !isRunning) release()
    lock.withLock {
        updateRecord(id) {
            requireHumanConversation()
            when (edit) {
                is SessionEdit.Rename -> copy(title = edit.title)
                is SessionEdit.SetArchived -> copy(isArchived = edit.isArchived)
                is SessionEdit.SetPinned -> copy(isPinned = edit.isPinned)
                is SessionEdit.SetUnread -> copy(isUnread = edit.isUnread)
            }
        }
    }
}
