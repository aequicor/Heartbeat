package io.aequicor.heartbeat.feature.checklist.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Opens an independently owned screen for a remote studio view; callers close it with that view. */
public fun interface ChecklistRemoteScreens {
    /** Opens the checklist card identified by [id] in the current profile. */
    public fun open(id: String): ChecklistRemoteScreen
}

/** A desktop-owned child screen. JSON keeps UI and FlowMVI out of the feature API. */
public interface ChecklistRemoteScreen {
    /** Committed projections. Revisions increase within this handle's lifetime. */
    public val states: Flow<ChecklistViewState>

    /** Returns the committed projection after applying [intent], including the current machine state. */
    public suspend fun dispatch(intent: JsonObject): ChecklistViewState

    /** Releases the screen and cancels pending dispatch waiters; the profile machine keeps running. */
    public fun close()
}

/** [revision] belongs to the handle; [screen] is the serializable screen-model projection. */
public data class ChecklistViewState(val revision: Long, val screen: JsonObject)
