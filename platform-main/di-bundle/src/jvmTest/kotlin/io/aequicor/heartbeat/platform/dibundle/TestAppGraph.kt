package io.aequicor.heartbeat.platform.dibundle

import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.InstanceKeeperOwner
import com.arkivanov.essenty.statekeeper.StateKeeper
import com.arkivanov.essenty.statekeeper.StateKeeperOwner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.StringKey
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageMaintenance
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.SharedFactory
import io.aequicor.heartbeat.core.di.SharedKey
import io.aequicor.heartbeat.core.profilefacade.ActiveProfileStorage
import io.aequicor.heartbeat.core.profilefacade.ProfileId
import io.aequicor.heartbeat.core.secrets.impl.SecretsConfig
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files

/**
 * App graph for integration tests: the real contributions of all modules plus the test ones below.
 * [PersistedProfile] plays the role of disk — it outlives a graph, i.e. "survives process death".
 * Storages of core:datastore live in its temporary [PersistedProfile.storageRoot].
 */
@DependencyGraph(
    AppScope::class,
    // The studio is compiled into this graph (see build.gradle.kts), so it is not imported from its bundle.
    excludes = [StudioImportBindings::class, StudioAppImportBindings::class],
)
interface TestAppGraph : HeartbeatGraph {
    val acpClients: AcpClientFactory
    val acpStdio: AcpStdioTransportFactory
    val scopes: ScopeFactory

    @ForScope(AppScope::class)
    val appScope: ScopeHandle

    @ForScope(AppScope::class)
    val appStores: DataStores

    val storageMaintenance: StorageMaintenance
    override val machines: MachineRegistry
    val httpClient: HttpClient

    val httpEngine: HttpClientEngine

    @DependencyGraph.Factory
    interface Factory {
        fun create(
            @Provides persisted: PersistedProfile,
            @Provides secretsConfig: SecretsConfig = persisted.secretsConfig,
        ): TestAppGraph
    }
}

class PersistedProfile(
    val isOperationSuspensionEnabled: Boolean = true,
    private val storageRootOverride: String? = null,
) {
    var id: ProfileId? = null
    var beforeProfileWipe: suspend () -> Unit = {}
    val secretsConfig: SecretsConfig by lazy {
        SecretsConfig("heartbeat.test." + java.util.UUID.randomUUID(), storageRoot + "/secrets", isDevelopment = true)
    }

    /**
     * Storage directory of the "device"; created lazily, deleted by the tests that use storages. A fresh device
     * starts from [LEGACY_TOGGLE_BASELINE]: the integration tests drive each feature through its own explicit
     * override, and the periodic work of features that now ship enabled never ends in virtual test time.
     */
    val storageRoot: String by lazy {
        (storageRootOverride ?: Files.createTempDirectory("hb-storage").toString()).also(::seedToggleBaseline)
    }
}

/** Toggles that ship enabled; the tests keep them off until a test turns one on explicitly. */
private val LEGACY_TOGGLE_BASELINE = listOf(
    "ai.koog", "ai.koog.coding_tools", "ai_studio.engine_runtime", "ai_studio.local_projects",
    "ai_studio.safety_reviews_indicator", "ai.engine_connections", "search.engine_tools", "checklist.enabled",
    "questionnaire.enabled", "scheduler.enabled", "scheduler.actions", "plantuml.enabled", "browser.enabled",
    "browser.agent_control", "browser.error_pages", "agent_learning.enabled", "harness.enabled",
    "organic_ai.enabled", "context_optimization.enabled", "worktree_mode.enabled", "worktree_mode.sandbox",
    "service_ui.enabled", "service_ui.web", "adaptive_subagents.enabled", "adaptive_subagents.router",
    "adaptive_subagents.session_tools", "autocomplete.enabled", "attachments.enabled", "ai.engine_management",
    "ai.engine_configuration", "browser.page_zoom", "browser.research_access", "project_config.enabled",
    "research_chat.enabled", "feedback.enabled", "context_optimization.continuation_brief",
    "context_optimization.ledger_tools",
)

/**
 * Writes the DataStore preferences file of the app-wide toggle overrides, every baseline toggle set to false.
 * Each entry is a length-prefixed (key, boolean value) pair of the preferences protobuf; an existing file is kept.
 */
private fun seedToggleBaseline(root: String) {
    val file = java.io.File(root, "storage/app/kv/core_feature_toggles.preferences_pb")
    if (file.exists()) return
    file.parentFile.mkdirs()
    val bytes = java.io.ByteArrayOutputStream()
    for (key in LEGACY_TOGGLE_BASELINE) {
        val name = key.encodeToByteArray()
        val entry = byteArrayOf(0x0a, name.size.toByte()) + name + byteArrayOf(0x12, 0x02, 0x08, 0x00)
        bytes.write(byteArrayOf(0x0a, entry.size.toByte()))
        bytes.write(entry)
    }
    file.writeBytes(bytes.toByteArray())
}

/** Keeps the storages of core:datastore in the temporary directory of the "device". */
@ContributesBinding(AppScope::class, priority = 1)
@Inject
class TestStorageRoot(private val persisted: PersistedProfile) : StorageRoot {
    override fun path(): String = persisted.storageRoot
}

/** Accessor to profile-owned storages. */
@ContributesTo(ProfileScope::class)
interface TestStorageAccessors {
    @ForScope(ProfileScope::class)
    val stores: DataStores
}

/** Overrides the DataStore implementation of core:datastore:impl (priority 0) and the in-memory default. */
@ContributesBinding(AppScope::class, priority = 1)
@Inject
class FakeActiveProfileStorage(private val persisted: PersistedProfile) : ActiveProfileStorage {
    // Usually suspends like DataStore; tests can also exercise an in-memory implementation with no suspension.
    override suspend fun read(): ProfileId? {
        if (persisted.isOperationSuspensionEnabled) yield()
        return persisted.id
    }

    override suspend fun write(id: ProfileId?) {
        if (persisted.isOperationSuspensionEnabled) yield()
        persisted.id = id
    }
}

// ---- a feature: its own scope and graph extension, contributed to ProfileScope ----

interface TestFeatureScope

@GraphExtension(TestFeatureScope::class)
interface TestFeatureGraph {
    val draft: Draft
    val machine: Machine<CounterState, CounterIntent, CounterOutput>

    @ForScope(TestFeatureScope::class)
    val scope: ScopeHandle

    @ContributesTo(ProfileScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Provides @ForScope(TestFeatureScope::class) scope: ScopeHandle,
        ): TestFeatureGraph
    }
}

/** Feature-scoped object with state that must survive process death. */
@SingleIn(TestFeatureScope::class)
@Inject
class Draft(
    @ForScope(TestFeatureScope::class) scope: ScopeHandle,
) {
    var text: String? = scope.savedState.consume(KEY, String.serializer())

    init {
        scope.savedState.register(KEY, String.serializer()) { text }
    }

    private companion object {
        const val KEY = "draft"
    }
}

/** Accessor pattern: platform code reaches profile-level entry points by casting the ProfileGraph. */
@ContributesTo(ProfileScope::class)
interface TestProfileAccessors {
    val featureGraphs: TestFeatureGraph.Factory
    val scopes: ScopeFactory
}

// ---- a shared object ----

object CounterKey : SharedKey<Counter> {
    override val name = "counter"
}

data class Counter(val scope: ScopeHandle)

@ContributesIntoMap(ProfileScope::class)
@StringKey("counter")
@Inject
class CounterFactory : SharedFactory<Counter> {
    override fun create(scope: ScopeHandle) = Counter(scope)
}

object BrokenKey : SharedKey<Counter> {
    override val name = "broken"
}

@SingleIn(ProfileScope::class) // the test inspects the same instance SharedScopes uses
@ContributesIntoMap(ProfileScope::class)
@StringKey("broken")
@Inject
class BrokenFactory : SharedFactory<Counter> {
    var lastScope: ScopeHandle? = null

    override fun create(scope: ScopeHandle): Counter {
        lastScope = scope
        error("factory failure")
    }
}

/** Accessor to reach the failing factory from the test. */
@ContributesTo(ProfileScope::class)
interface TestSharedAccessors {
    val sharedFactories: Map<String, SharedFactory<*>>
}

/** Minimal Decompose-like component: what `ComponentContext` provides to the extensions. */
class TestComponent(override val instanceKeeper: InstanceKeeper, override val stateKeeper: StateKeeper) :
    InstanceKeeperOwner,
    StateKeeperOwner
