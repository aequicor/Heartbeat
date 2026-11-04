package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.di

import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekBackend
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.domain.DeepSeekEngineManager
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTrees
import kotlinx.coroutines.flow.emptyFlow
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeepSeekBindingsTest {
    @Test
    fun `the user's CLI is discovered, never installed, and managed without touching it`() {
        val registration = DeepSeekBindings.registration(
            backend = lazy<DeepSeekBackend> { error("The backend must not be constructed") },
            manager = lazy<DeepSeekEngineManager> { error("The manager must not be constructed") },
        )

        assertEquals(InstallSupport.External, registration.management.install)
        assertEquals(LoginSupport.Connections, registration.management.login)
        assertNotNull(registration.manager)
        assertNotNull(registration.fileSessionTrees)
        assertEquals("DeepSeek Harness", registration.descriptor.title)
        assertTrue(SessionTrees.id in registration.descriptor.declaredFeatures)
        assertEquals(3, registration.modelCatalogRevision)
    }

    @Test
    fun `file tree reader is resolved lazily from the backend`() {
        val trees = object : SessionTrees {
            override fun observe(root: SessionRef, access: SessionTreeAccess) = emptyFlow<SessionTreeSnapshot>()
            override suspend fun history(root: SessionRef, ref: SessionRef, access: SessionTreeAccess) = error("unused")
        }
        val backend = Proxy.newProxyInstance(
            DeepSeekBackend::class.java.classLoader,
            arrayOf(DeepSeekBackend::class.java),
        ) { _, method, _ -> if (method.name == "getNativeTrees") trees else error("Unexpected ${method.name}") }
            as DeepSeekBackend
        val registration = DeepSeekBindings.registration(
            backend = lazyOf(backend),
            manager = lazy<DeepSeekEngineManager> { error("unused") },
        )
        assertSame(trees, registration.fileSessionTrees?.value)
    }
}
