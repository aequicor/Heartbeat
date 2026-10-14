package io.aequicor.heartbeat.feature.harness.api

import kotlin.test.Test
import kotlin.test.assertNotNull

class HarnessReviewedScopeTest {
    @Test
    fun `reviewed connections reject changed source and changed membership`() {
        val attach = HarnessIntent.Public.Attach(request, harness.id, session, 0, emptySet())
        val detach = HarnessIntent.Public.Detach(request, harness.id, session, 0, emptySet())
        val changed = ready.copy(harnesses = listOf(HarnessEntry(harness.copy(revision = 1))))
        changed.refuses(attach, HarnessRejection.Conflict)
        changed.refuses(detach, HarnessRejection.Conflict)
        val connected = ready.copy(attachments = mapOf(session to setOf(harness.id)))
        connected.refuses(attach, HarnessRejection.Conflict)
        connected.refuses(detach, HarnessRejection.Conflict)
        assertNotNull(ready.send(attach).attachmentWrite)
    }

    @Test
    fun `reviewed source edits and deletion reject changed connections`() {
        val edit = HarnessIntent.Internal.Update(
            request,
            harness.id,
            HarnessChange.Meta("New", "Description"),
            0,
            HarnessAuthor.Agent(session),
            now,
            expectedSessions = emptySet(),
        )
        val delete = HarnessIntent.Internal.Delete(request, harness.id, 0, expectedSessions = emptySet())
        val connected = ready.copy(attachments = mapOf(session to setOf(harness.id)))
        connected.refuses(edit, HarnessRejection.Conflict)
        connected.refuses(delete, HarnessRejection.Conflict)
        val connecting = ready.send(HarnessIntent.Public.Attach(request, harness.id, session))
        connecting.refuses(edit, HarnessRejection.Busy)
        connecting.refuses(delete, HarnessRejection.Busy)
    }

    @Test
    fun `all connections wait for an admitted source mutation`() {
        val edit = HarnessIntent.Internal.Update(
            request,
            harness.id,
            HarnessChange.Meta("New", "Description"),
            0,
            HarnessAuthor.Agent(session),
            now,
        )
        val pending = ready.send(edit)
        pending.refuses(HarnessIntent.Public.Attach(request, harness.id, session, 0), HarnessRejection.Busy)
        pending.refuses(HarnessIntent.Public.Attach(request, harness.id, session), HarnessRejection.Busy)
        pending.refuses(HarnessIntent.Public.Detach(request, harness.id, session), HarnessRejection.Busy)
    }
}
