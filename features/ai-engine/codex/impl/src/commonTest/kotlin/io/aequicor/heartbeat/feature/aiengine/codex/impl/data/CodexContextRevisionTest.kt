@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextRevision
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodexContextRevisionTest {
    @Test
    fun `compaction retention is independent of usage and ignores foreign threads`() = runTest {
        val fixture = Fixture(this)
        fixture.isUsageEnabled = false
        val session = fixture.open()
        val revision = assertIs<FeatureAccess.Available<SessionContextRevision>>(
            session.features.resolve(SessionContextRevision),
        ).feature
        val original = assertNotNull(revision.state.value)
        fixture.wire.event("thread/compacted", json("threadId" to "foreign".json()))
        runCurrent()
        assertEquals(original, revision.state.value)
        val item = json("id" to "compaction".json(), "type" to "contextCompaction".json())
        fixture.event("item/started", "item" to item)
        runCurrent()
        assertNull(revision.state.value)
        fixture.event("item/completed", "item" to item)
        runCurrent()
        val compacted = assertNotNull(revision.state.value)
        assertNotEquals(original, compacted)
        fixture.event("thread/compacted")
        runCurrent()
        assertNotEquals(compacted, assertNotNull(revision.state.value))
        fixture.runtime.close()
    }
}
