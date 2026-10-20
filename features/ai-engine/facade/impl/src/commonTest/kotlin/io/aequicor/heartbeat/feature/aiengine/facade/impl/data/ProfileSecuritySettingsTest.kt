package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileSecuritySettingsTest {
    @Test
    fun `existing choices survive recreation and never leak to another profile`() = runTest {
        val disk = SecurityMemoryStore()
        val settings = ProfileSecuritySettings(disk)
        assertNull(settings.load())
        assertEquals(SecurityMode.Automatic, settings.modes.first())
        for (mode in SecurityMode.entries) {
            settings.save(mode)
            assertEquals(mode, ProfileSecuritySettings(disk).load())
            assertEquals(mode, settings.modes.first())
            assertNull(ProfileSecuritySettings(SecurityMemoryStore()).load())
        }
    }

    @Test
    fun `unknown stored choice stays intact and execution requires confirmation`() = runTest {
        val disk = SecurityMemoryStore()
        val settings = ProfileSecuritySettings(disk)
        for (raw in listOf("", "Unknown", "{broken", "\"Trust\"")) {
            disk.value.value = raw
            assertFailsWith<IllegalStateException> { settings.load() }
            assertEquals(SecurityMode.Ask, settings.modes.first())
            assertEquals(raw, disk.value.value)
        }
    }

    @Test
    fun `pending and failed writes cannot grant trust`() = runTest {
        val disk = SecurityMemoryStore()
        val settings = ProfileSecuritySettings(disk)
        settings.save(SecurityMode.Ask)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        disk.beforeWrite = {
            entered.complete(Unit)
            resume.await()
            error("disk unavailable")
        }
        val write = async { assertFailsWith<IllegalStateException> { settings.save(SecurityMode.Trust) } }
        entered.await()
        assertEquals(SecurityMode.Ask, settings.modes.first())
        resume.complete(Unit)
        assertEquals("disk unavailable", write.await().message)
        assertEquals(SecurityMode.Ask, settings.load())
    }
}

private class SecurityMemoryStore : KeyValueStore {
    override val spec = KeyValueSpec("ai_studio_security")
    val value = MutableStateFlow<String?>(null)
    var beforeWrite: suspend () -> Unit = {}

    @Suppress("UNCHECKED_CAST") // This fake accepts only the persisted string mode.
    override fun <T : Any> observe(key: StoreKey<T>) = value.map { it as T? }

    @Suppress("UNCHECKED_CAST") // This fake accepts only the persisted string mode.
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = value.value as T?

    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        check(key.name == "confirmation_level")
        beforeWrite()
        this.value.value = value as String
    }

    override suspend fun remove(key: StoreKey<*>) {
        value.value = null
    }

    override suspend fun clear() {
        value.value = null
    }
}
