package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.connections.api.BindingModels
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelection
import io.aequicor.heartbeat.feature.aiengine.connections.api.ModelSelections
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReviewer
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEffect
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigMachineSpec
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigState
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBindingStorage
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBundles
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal val PROJECT = WorkspaceRef("project")
internal val TARGET = EngineTarget(EngineId("claude"), EngineBindingId("b"), ModelId("sonnet"))

/** Everything the effects touch, in memory; [run] drives the real spec with the real effects inline. */
internal class EffectsFixture(vararg sections: ConfigSection) {
    val folders = MemoryFolders()
    val bundles = MemoryBundles()
    val workspaces = Workspaces(mapOf(PROJECT to "/work/project"))
    val storage = MemoryStorage()
    val reviewer = Reviewer()
    val selections = Selections(TARGET)
    val access = ConfigFolderAccess(workspaces, folders, lazyOf(sections.toSet()))
    val effects = ProjectConfigEffects(
        access,
        ConfigTasks(access, bundles),
        storage,
        lazyOf(reviewer),
        selections,
        object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
        },
    )
    var state: ProjectConfigState = ProjectConfigState.Off
    val outputs = mutableListOf<ProjectConfigOutput>()

    /** Folder files of [PROJECT]. */
    val files: MutableMap<String, String> get() = folders.trees.getOrPut("/work/project") { mutableMapOf() }

    /** Results [effect] sends. */
    suspend fun results(effect: ProjectConfigEffect): List<ProjectConfigIntent> {
        val sent = mutableListOf<ProjectConfigIntent>()
        effects.handle(
            effect,
            object : EffectScope<ProjectConfigIntent> {
                override suspend fun send(intent: ProjectConfigIntent): SendResult {
                    sent += intent
                    return SendResult.Accepted
                }
            },
        )
        return sent
    }

    /** Sends [intent] to the spec and runs every effect inline until nothing is left. */
    suspend fun run(intent: ProjectConfigIntent) {
        val resolution = ProjectConfigMachineSpec.resolve(state, intent) ?: return
        state = resolution.to
        outputs += resolution.outputs
        resolution.effects.forEach { effect -> results(effect).forEach { run(it) } }
    }

    val ready: ProjectConfigState.Ready get() = state as ProjectConfigState.Ready
}

/** Project folders as maps of tree paths to texts. */
internal class MemoryFolders : ProjectFolders {
    val trees = mutableMapOf<String, MutableMap<String, String>>()
    var reads = 0
    var readProblems: List<ConfigProblem> = emptyList()
    var writeFailure: ConfigWriteFailure? = null
    var afterWrite: (() -> Unit)? = null
    override val isAvailable: Boolean = true

    override suspend fun read(root: String): ConfigRead = trees[root].also { reads++ }?.let { files ->
        ConfigRead.Read(ConfigTree.of(files.map { (path, text) -> ConfigFile(ConfigPath(path), text) }, readProblems))
    } ?: ConfigRead.Absent

    override suspend fun fingerprint(root: String): String? = trees[root]?.toSortedMap()?.toString()

    override suspend fun write(root: String, put: List<ConfigFile>, delete: List<ConfigPath>) {
        writeFailure?.let { throw ConfigWriteException(it) }
        val files = trees.getOrPut(root) { mutableMapOf() }
        put.forEach { files[it.path.value] = it.text }
        delete.forEach { files.remove(it.value) }
        afterWrite?.invoke()
    }
}

/** Bundles by path. */
internal class MemoryBundles : ConfigBundles {
    val written = mutableMapOf<String, ConfigTree>()
    override val isAvailable: Boolean = true

    override suspend fun read(path: String): ConfigRead = written[path]?.let {
        ConfigRead.Read(
            it,
        )
    } ?: ConfigRead.Absent

    override suspend fun write(path: String, tree: ConfigTree) {
        written[path] = tree
    }
}

/** Projects with fixed roots. */
internal class Workspaces(private val roots: Map<WorkspaceRef, String>) : LocalWorkspaces {
    override val isAvailable: Boolean = true
    override fun observe(): Flow<List<LocalWorkspace>> = flowOf(roots.keys.map { LocalWorkspace(it, it.value) })
    override suspend fun register(directory: String): LocalWorkspace = error("unused")
    override suspend fun resolve(ref: WorkspaceRef): String? = roots[ref]
}

/** Bindings in memory. */
internal class MemoryStorage : ConfigBindingStorage {
    var bindings: List<ConfigBinding> = emptyList()
    override suspend fun load(): List<ConfigBinding> = bindings
    override suspend fun save(bindings: List<ConfigBinding>) {
        this.bindings = bindings
    }
}

/** Answers every review with [verdict] and records what it was asked. */
internal class Reviewer(var verdict: String = "safe") : AgentSafetyReviewer {
    val changes = mutableListOf<Pair<String, String>>()
    override suspend fun review(target: EngineTarget, policy: String, change: String): AgentSafetyReview {
        changes += policy to change
        return AgentSafetyReview(verdict, "because")
    }
}

/** A fixed model selection with [default]. */
internal class Selections(default: EngineTarget?) : ModelSelections {
    val selection = MutableStateFlow(
        ModelSelection(listOf(BindingModels(TARGET.binding, setOf(TARGET.model))), default),
    )

    override fun observe(): Flow<ModelSelection> = selection

    override suspend fun update(change: (ModelSelection) -> ModelSelection): ModelSelection =
        change(selection.value).also { selection.value = it }
}
