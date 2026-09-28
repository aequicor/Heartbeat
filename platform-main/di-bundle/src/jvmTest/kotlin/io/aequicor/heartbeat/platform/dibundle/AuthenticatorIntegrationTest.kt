package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.createGraphFactory
import io.aequicor.heartbeat.core.di.OwnedScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthChecks
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthContextKey
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthVerdict
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.spi.AuthCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Profile entry points of the authentication services, for this test only. */
@ContributesTo(ProfileScope::class)
interface AuthenticatorTestAccessors {
    val authSources: AuthSources
    val authChecks: AuthChecks
    val authCredentials: AuthCredentials
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthenticatorIntegrationTest {
    private val persisted = PersistedProfile()
    private val app = createGraphFactory<TestAppGraph.Factory>().create(persisted)
    private val id = ProfileId("auth-integration")
    private val scope = AuthScope(ProviderId("openai"), EndpointOrigin("https://api.example.com"))
    private val context = AuthContextKey("engine.default")

    @BeforeTest
    fun setUp() {
        val os = System.getProperty("os.name")
        assumeTrue(os.startsWith("Windows") || os.startsWith("Mac"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        (app.appScope as OwnedScope).close()
        Dispatchers.resetMain()
        File(persisted.storageRoot).deleteRecursively()
    }

    private suspend fun accessors() = app.profileSessions.open(id).graph as AuthenticatorTestAccessors

    @Test
    fun managedKeySurvivesProfileReopenAndIsRemovedWhenForgotten() = runTest {
        val sources = accessors().authSources
        val source = Secret("sk-private-value".toCharArray()).use { sources.addManagedKey("work", scope, it) }
        app.profileSessions.close()

        val reopened = accessors()
        assertEquals(source, reopened.authSources.get(source.info.id))
        assertEquals(AuthVerdict.Authenticated, reopened.authChecks.check(source.info.id, context).verdict)
        assertEquals(
            "sk-private-value",
            reopened.authCredentials.managedKey(source).use { key -> key.reveal { it.concatToString() } },
        )

        reopened.authSources.forget(source.info.id)
        assertEquals(AuthVerdict.SourceUnavailable, reopened.authChecks.check(source.info.id, context).verdict)
        assertFailsWith<Exception> { reopened.authCredentials.managedKey(source) }
    }
}
