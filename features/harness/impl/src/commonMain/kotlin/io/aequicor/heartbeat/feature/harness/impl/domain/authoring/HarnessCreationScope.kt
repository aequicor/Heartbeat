package io.aequicor.heartbeat.feature.harness.impl.domain.authoring

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessScope

/** Resolves where a new harness is offered; activation still requires an explicit chat connection. */
internal fun interface HarnessCreationScope {
    /** Detached chats use Attached; project identities are normalized; null means the workspace is unknown. */
    suspend fun resolve(workspace: WorkspaceRef?): HarnessScope?
}
