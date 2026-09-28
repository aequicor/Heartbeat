package io.aequicor.heartbeat.feature.welcome.impl.domain

import io.aequicor.heartbeat.core.profilefacade.ProfileGraph
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.profilefacade.ProfileSession
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.welcome.api.WelcomeEffect
import io.aequicor.heartbeat.feature.welcome.api.WelcomeIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WelcomeEffectsTest {
    private val sent = mutableListOf<WelcomeIntent>()
    private val profiles = FakeProfiles()
    private val feedback = object : EffectScope<WelcomeIntent> {
        override suspend fun send(intent: WelcomeIntent): SendResult {
            sent += intent
            return SendResult.Accepted
        }
    }

    @Test
    fun `both preference values reach the machine through the domain port`() = runTest {
        listOf(true, false).forEach { enabled ->
            WelcomeEffects(settings { enabled }, profiles).handle(WelcomeEffect.ReadSettings, feedback)
        }
        assertEquals(
            listOf<WelcomeIntent>(WelcomeIntent.Internal.Configured(true), WelcomeIntent.Internal.Configured(false)),
            sent,
        )
    }

    @Test
    fun `settings failure propagates without acknowledging a successful read`() = runTest {
        val effects = WelcomeEffects(settings { throw IllegalStateException("unavailable") }, profiles)
        assertFailsWith<IllegalStateException> { effects.handle(WelcomeEffect.ReadSettings, feedback) }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `studio opens the local profile once and then reports it`() = runTest {
        val effects = WelcomeEffects(settings { true }, profiles)
        effects.handle(WelcomeEffect.OpenProfile, feedback)
        effects.handle(WelcomeEffect.OpenProfile, feedback)
        assertEquals(listOf(ProfileId("local")), profiles.opened)
        assertEquals(
            listOf<WelcomeIntent>(WelcomeIntent.Internal.ProfileOpened, WelcomeIntent.Internal.ProfileOpened),
            sent,
        )
    }

    @Test
    fun `profile failure propagates without reporting an open profile`() = runTest {
        profiles.failure = IllegalStateException("storage")
        assertFailsWith<IllegalStateException> {
            WelcomeEffects(settings { true }, profiles).handle(WelcomeEffect.OpenProfile, feedback)
        }
        assertTrue(sent.isEmpty())
    }

    private fun settings(read: suspend () -> Boolean): WelcomeSettings = object : WelcomeSettings {
        override suspend fun isIntroEnabled(): Boolean = read()
    }
}

private class FakeProfiles : ProfileSessions {
    val opened = mutableListOf<ProfileId>()
    var failure: Exception? = null
    override val active = MutableStateFlow<ProfileSession?>(null)

    override suspend fun restore(): ProfileSession? = active.value

    override suspend fun open(id: ProfileId): ProfileSession {
        failure?.let { throw it }
        opened += id
        return ProfileSession(id, UnusedGraph).also { active.value = it }
    }

    override suspend fun close() {
        active.value = null
    }
}

private object UnusedGraph : ProfileGraph {
    override val scope get() = error("Unused")
    override val sharedScopes get() = error("Unused")
}
