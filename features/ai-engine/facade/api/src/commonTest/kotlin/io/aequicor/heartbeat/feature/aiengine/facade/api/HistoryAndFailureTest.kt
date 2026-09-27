package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailure
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class HistoryAndFailureTest {
    private val checkpoint = HistoryCheckpoint("private-cursor")
    private val first = SessionItem.Message(ItemInfo(ItemId("m1"), 0, 1), MessageRole.User, TestPrompt.parts)

    @Test
    fun `history page rejects duplicates and unstable ordering`() {
        assertFailsWith<IllegalArgumentException> { page(listOf(first, first)) }
        val second = first.copy(info = ItemInfo(ItemId("m2"), 1, 0))
        assertFailsWith<IllegalArgumentException> { page(listOf(second, first)) }
        val snapshot = page(listOf(first, second))
        assertEquals(snapshot, Json.decodeFromString<HistoryPage>(Json.encodeToString(snapshot)))
    }

    @Test
    fun `page boundaries do not imply completeness of native history`() {
        val partial = page(listOf(first)).copy(coverage = HistoryCoverage.Partial)
        assertNull(partial.older)
        assertEquals(HistoryCoverage.Partial, partial.coverage)
        assertFailsWith<IllegalArgumentException> { HistoryPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> { PageRequest(limit = 501) }
        assertFalse(checkpoint.toString().contains("private-cursor"))
    }

    @Test
    fun `native session identifiers remain separate between stores and engines`() {
        val first = SessionRef(TestTarget.engine, SessionSourceId("native"), "session-1")
        assertNotEquals(first, first.copy(source = SessionSourceId("isolated")))
        assertNotEquals(first, first.copy(engine = EngineId("other")))
        assertEquals(ArchiveFilter.All, SessionQuery().archive)
        assertNull(SessionSummary(first).lastRoute)
    }

    @Test
    fun `events and pending states roundtrip without activating historical requests`() {
        val event: SessionEvent = SessionEvent.PermissionRequested(checkpoint, TestPermission)
        assertEquals(event, Json.decodeFromString<SessionEvent>(Json.encodeToString(event)))
        val state: ActiveSessionState = ActiveSessionState.AwaitingUserAction(TestTurn, listOf(TestPermission))
        assertEquals(state, Json.decodeFromString<ActiveSessionState>(Json.encodeToString(state)))
    }

    @Test
    fun `domain failures serialize separately from throwable diagnostics`() {
        val failures = listOf(
            EngineFailure.Authentication(AuthFailure(AuthFailureReason.CredentialsExpired)),
            EngineFailure.RateLimited(LimitScope.Model(TestTarget), Instant.fromEpochSeconds(20)),
            EngineFailure.QuotaExceeded(LimitScope.Binding(TestTarget.binding)),
            EngineFailure.ContextLimitExceeded(100),
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, TestPrompt.id),
        )
        failures.forEach { failure ->
            assertEquals(failure, Json.decodeFromString<EngineFailure>(Json.encodeToString<EngineFailure>(failure)))
            assertEquals(failure.code, EngineException(failure).message)
            assertNull(EngineException(failure).cause)
        }
        assertFalse(TestPrompt.toString().contains("private prompt"))
    }

    @Test
    fun `timeouts never authorize automatic resubmission and quotas never authorize credential fallback`() {
        assertEquals(RetryAdvice.Unknown, EngineFailure.Transport(TransportFailureReason.Timeout).retryAdvice())
        assertEquals(
            RetryAdvice.Unknown,
            EngineFailure.Request(RequestFailureReason.OutcomeUnknown, TestPrompt.id).retryAdvice(),
        )
        assertEquals(RetryAdvice.AfterUserAction, EngineFailure.QuotaExceeded(LimitScope.Unknown).retryAdvice())
        assertEquals(RetryAdvice.AfterUserAction, EngineFailure.ContextLimitExceeded().retryAdvice())
        assertEquals(RetryAdvice.Never, EngineFailure.Lifecycle(LifecycleFailureReason.ProfileClosed).retryAdvice())
    }

    private fun page(items: List<SessionItem>): HistoryPage = HistoryPage(
        items,
        null,
        null,
        checkpoint,
        HistoryCoverage.Complete,
    )
}
