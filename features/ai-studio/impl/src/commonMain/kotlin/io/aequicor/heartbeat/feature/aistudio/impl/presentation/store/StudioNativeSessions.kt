package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionActivity
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeCoverage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionTreeSnapshot
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioOutput
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.sessionOf
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import pro.respawn.flowmvi.api.PipelineContext

/** Root-scoped family and the certainty of its activity count. */
@Immutable
data class SessionTreeUi(val sessions: ImmutableList<SubSessionUi>, val activeCount: Int, val isActivityKnown: Boolean)

/** Selection identity prevents a previous child transcript flashing during a switch. */
@Immutable
data class NativeTranscriptUi(val key: String, val messages: ImmutableList<MessageUi>)

/** Stable roots come from the repository; changing only selection never restarts the tree subscription. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StudioNativeSessions(
    private val studio: MachineRef<AiStudioState, AiStudioIntent, AiStudioOutput>,
    private val backend: StudioBackend,
    private val selection: Flow<Map<String, String>>,
    private val organisms: Flow<Map<String, Organism>>,
    private val attachments: (List<MessageUi>) -> Flow<ImmutableList<MessageUi>>,
) {
    private val log = Log.tag("StudioNativeSessions")
    private val trees = MutableStateFlow<Map<String, Pair<Root, SessionTreeSnapshot>>>(emptyMap())

    private data class Root(
        val chat: String,
        val ref: SessionRef,
        val access: SessionTreeAccess,
        val owner: String = PrimarySubSession,
        val name: String? = null,
    )

    suspend fun observe(
        pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    ): Unit = coroutineScope {
        val views = backend.sessionViews()
        launch {
            roots().flatMapLatest { roots -> snapshots(roots) }.collect { snapshots ->
                log.v { "Native tree observations updated" }
                trees.value = snapshots
            }
        }
        launch {
            combine(trees, organisms) { snapshots, organic ->
                snapshots.values.groupBy { it.first.chat }.mapValues { (chat, families) ->
                    val organism = organic[chat]?.toUi()
                    if (organism == null) {
                        families.first().let { it.second.toUi(it.first.name) }
                    } else {
                        mergeSessionTrees(organism, families.associate { it.first.owner to it.second })
                    }
                }
            }.collect { rendered ->
                pipeline.updateState { copy(nativeTrees = rendered.toImmutableMap()) }
            }
        }
        launch {
            combine(trees, selection) { families, selected ->
                families.values.mapNotNull { pair ->
                    val (root, tree) = pair
                    val key = selected[root.chat] ?: PrimarySubSession
                    tree.descendants().firstOrNull { nativeKey(it.node.ref) == key }
                        ?.let { Triple(root, it.node.ref, key) }
                }
            }.distinctUntilChanged().flatMapLatest { shown ->
                if (shown.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        shown.map { (root, ref, key) ->
                            flow {
                                emit(root.chat to NativeTranscriptUi(key, persistentListOf()))
                                emitAll(
                                    views.observeNative(root.ref, ref, root.access)
                                        .flatMapLatest { messages -> attachments(messages.map { it.toUi() }) }
                                        .map { root.chat to NativeTranscriptUi(key, it) },
                                )
                            }
                        },
                    ) { it.toMap() }
                }
            }.collect { transcripts ->
                pipeline.updateState { copy(nativeTranscripts = transcripts.toImmutableMap()) }
            }
        }
    }

    private fun roots(): Flow<List<Root>> = flow {
        emitAll(
            combine(
                backend.repository().observeWorkspace(),
                organisms,
                studio.state.map { state ->
                    (state as? AiStudioState.Ready)?.panes.orEmpty().mapNotNull { it.sessionId }.toSet()
                }.distinctUntilChanged(),
            ) { workspace, organic, open ->
                workspace.sessions.filter { it.id in open }.flatMap { session ->
                    rootsOf(session, organic[session.id])
                }
            }.distinctUntilChanged(),
        )
    }

    private fun rootsOf(
        session: io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioSession,
        organism: Organism?,
    ): List<Root> = if (session.isOrganism && organism != null) {
        organism.cells.mapNotNull { cell ->
            organism.sessionOf(cell.id.value)?.let {
                Root(
                    session.id,
                    it.ref,
                    SessionTreeAccess(it.reopening.target, it.reopening.workspace),
                    cell.id.value,
                )
            }
        }.distinctBy { it.ref }
    } else {
        val ref = session.nativeSession
        val access = session.treeAccess
        if (ref != null && access != null) listOf(Root(session.id, ref, access, name = session.title)) else emptyList()
    }

    private fun snapshots(roots: List<Root>): Flow<Map<String, Pair<Root, SessionTreeSnapshot>>> = flow {
        if (roots.isEmpty()) {
            emit(emptyMap())
        } else {
            emitAll(
                combine(
                    roots.map { root ->
                        observeRoot(root).map { (root.chat + "/" + root.owner) to (root to it) }
                    },
                ) { it.toMap() },
            )
        }
    }
    private fun observeRoot(root: Root): Flow<SessionTreeSnapshot> = flow {
        var previous = SessionTreeSnapshot(root.ref)
        while (true) {
            try {
                backend.sessionViews().tree(root.ref, root.access).collect {
                    previous = it
                    emit(it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Native session tree unavailable; retrying" }
                emit(
                    previous.copy(
                        nodes = previous.nodes.map { it.copy(activity = SessionActivity.Unknown) },
                        coverage = SessionTreeCoverage.Unavailable,
                    ),
                )
            }
            if (previous.coverage == SessionTreeCoverage.Unsupported) return@flow
            delay(TREE_RETRY_MILLIS)
        }
    }
}

private const val TREE_RETRY_MILLIS = 2_000L

internal fun nativeKey(ref: SessionRef): String = "native:" + Json.encodeToString(SessionRef.serializer(), ref)

internal fun SessionTreeSnapshot.toUi(rootName: String? = null): SessionTreeUi = SessionTreeUi(
    sessions = (
        listOf(
            SubSessionUi(
                PrimarySubSession,
                SubSessionKindUi.Root,
                rootName ?: nodes.firstOrNull { it.ref == root }?.name.orEmpty(),
                nodes.firstOrNull { it.ref == root }?.activity?.toUi() ?: SubSessionStateUi.Unknown,
                isViewable = true,
            ),
        ) + descendants().map {
            SubSessionUi(
                nativeKey(it.node.ref),
                SubSessionKindUi.Agent,
                it.node.name,
                it.node.activity.toUi(),
                isViewable = true,
                depth = it.depth,
            )
        }
    ).toImmutableList(),
    activeCount = activeCount,
    isActivityKnown = isActivityKnown,
)

private fun SessionActivity.toUi(): SubSessionStateUi = when (this) {
    SessionActivity.Queued -> SubSessionStateUi.Queued
    SessionActivity.Running -> SubSessionStateUi.Working
    SessionActivity.AwaitingUser -> SubSessionStateUi.AwaitingUser
    SessionActivity.Idle -> SubSessionStateUi.Resting
    SessionActivity.Completed -> SubSessionStateUi.Completed
    SessionActivity.Cancelled -> SubSessionStateUi.Cancelled
    SessionActivity.Failed -> SubSessionStateUi.Failed
    SessionActivity.Unknown -> SubSessionStateUi.Unknown
}

/** Native roots are already represented by organic cells; count each descendant identity once. */
internal fun mergeSessionTrees(organism: OrganismUi, families: Map<String, SessionTreeSnapshot>): SessionTreeUi {
    val owners = families.values.map { it.root }.toSet()
    val seen = owners.toMutableSet()
    var active = 0
    val sessions = organism.subSessions.flatMap { owner ->
        val descendants = families[owner.key]?.descendants().orEmpty().filter { seen.add(it.node.ref) }
        active += descendants.count { it.node.activity.isActive }
        listOf(owner) + descendants.map {
            SubSessionUi(
                nativeKey(it.node.ref),
                SubSessionKindUi.Agent,
                it.node.name,
                it.node.activity.toUi(),
                isViewable = true,
                depth = owner.depth + it.depth,
            )
        }
    }
    return SessionTreeUi(
        sessions.toImmutableList(),
        organism.activeDescendants() + active,
        families.values.all { it.isActivityKnown },
    )
}
