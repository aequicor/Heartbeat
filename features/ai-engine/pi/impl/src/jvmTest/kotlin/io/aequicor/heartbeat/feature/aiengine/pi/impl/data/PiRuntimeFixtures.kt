package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import androidx.room.RoomDatabase
import io.aequicor.heartbeat.core.datastore.DataEvent
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.DatabaseSpec
import io.aequicor.heartbeat.core.datastore.KeyValueSpec
import io.aequicor.heartbeat.core.datastore.KeyValueStore
import io.aequicor.heartbeat.core.datastore.Retention
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.datastore.StoreKey
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSecretId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResumeSessionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.RuntimeIdentity
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngineId
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject

internal suspend fun TestScope.runtimeFixture(): RuntimeFixture {
    val settings = PiSettings(RuntimeStores())
    settings.bind(RuntimeTarget.binding, RuntimeSource)
    val processes = RuntimeProcesses()
    val services = PiRuntimeServices(settings, processes, RuntimeWorkspaces, RuntimeWeb)
    val runtime = PiRuntime(
        PiRuntimeCredentials(
            RuntimeIdentity(PiEngineId, RuntimeSource.info.id, RuntimeSource.info.revision),
            RuntimeSource,
            "fingerprint",
        ),
        piTestEnvironment(enginesEnabled = true),
        services,
    )
    return RuntimeFixture(runtime, settings, processes)
}

internal data class RuntimeFixture(val runtime: PiRuntime, val settings: PiSettings, val processes: RuntimeProcesses)

internal val RuntimeSource = AuthSource.ManagedKey(
    AuthSourceInfo(AuthSourceId("source"), "Key", AuthRevision.Known("1")),
    AuthScope(ProviderId("anthropic"), EndpointOrigin("https://api.anthropic.com")),
    AuthSecretId("key"),
)
internal val RuntimeTarget = EngineTarget(PiEngineId, EngineBindingId("binding"), ModelId("anthropic/test"))
internal val RuntimeRequest = ResumeSessionRequest(RuntimeTarget)
internal val RuntimeRef = SessionRef(PiEngineId, PiSessionSource, "native")

internal class RuntimeProcesses : PiProcesses {
    var fingerprint = "fingerprint"
    var transcript: suspend () -> String? = { "native.jsonl" }
    var beforeStart: suspend () -> Unit = {}
    var configure: (FakeConnection) -> Unit = {}
    var transcriptReads = 0
    val connections = mutableListOf<FakeConnection>()

    override suspend fun credentialFingerprint(source: AuthSource.ManagedKey): String = fingerprint
    override suspend fun transcript(nativeId: String): String? {
        transcriptReads++
        return transcript()
    }
    override suspend fun start(
        source: AuthSource.ManagedKey,
        workspace: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
    ): PiConnection {
        beforeStart()
        return FakeConnection().also {
            it.event = event
            it.failed = failed
            configure(it)
            connections += it
        }
    }
}

private object RuntimeWorkspaces : LocalWorkspaces {
    override val isAvailable = false
    override fun observe() = flowOf(emptyList<LocalWorkspace>())
    override suspend fun register(directory: String): LocalWorkspace = error("No workspace requested")
    override suspend fun resolve(ref: WorkspaceRef): String? = error("No workspace requested")
}

private object RuntimeWeb : NativeWebFetch {
    override suspend fun fetch(url: String): ResourceContent = error("No web request expected")
}

private class RuntimeStores : DataStores {
    override val owner = StorageOwner.Profile(ProfileId("profile"))
    override fun keyValue(spec: KeyValueSpec): KeyValueStore = RuntimeKeyValueStore(spec)
    override fun <T : RoomDatabase> database(spec: DatabaseSpec<T>): T = error("No database expected")
    override suspend fun fire(event: DataEvent): Unit = error("No event expected")
}

private class RuntimeKeyValueStore(override val spec: KeyValueSpec) : KeyValueStore {
    private val values = mutableMapOf<String, Any>()
    override fun <T : Any> observe(key: StoreKey<T>) = flowOf(value(key))
    override suspend fun <T : Any> get(key: StoreKey<T>): T? = value(key)
    override suspend fun <T : Any> set(key: StoreKey<T>, value: T, retention: Retention) {
        values[key.name] = value
    }
    override suspend fun remove(key: StoreKey<*>) {
        values.remove(key.name)
    }
    override suspend fun clear() = values.clear()

    // Tests access the same typed key that wrote each value, as the real store does.
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> value(key: StoreKey<T>): T? = values[key.name] as T?
}
