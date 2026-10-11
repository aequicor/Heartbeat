package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.HarnessNativeTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.mergedToolPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Optional policy providers; lazy resolution prevents a cycle through harness session services. */
@ContributesTo(ProfileScope::class)
public interface ToolPolicyBindings {
    /** Profiles without harnesses have no policy providers. */
    @Multibinds(allowEmpty = true)
    public fun toolPolicyProviders(): Set<AgentToolPolicyProvider>
}

/**
 * Resolves effective policy and assigns monotonically increasing profile-local generations. Providers run outside
 * the lock, so one slow provider does not serialize other sessions; only generation assignment is serialized.
 * Snapshots are kept for the most recently resolved scopes; a forgotten scope gets a new generation.
 */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class ProfileToolPolicies(
    private val providers: Lazy<Set<AgentToolPolicyProvider>>,
    private val registry: EngineRegistry,
    private val toggles: FeatureToggles,
) : ToolPolicyResolver {
    private val log = Log.tag("ToolPolicies")
    private val lock = Mutex()

    // Insertion order is recency order: an entry is moved to the end whenever its scope is resolved.
    private val snapshots = LinkedHashMap<ToolPolicyScope, Snapshot>()
    private var generation = 0L
    private var lookups = 0L

    /** Number of remembered scopes; exposed for leak tests. */
    internal suspend fun snapshotCount(): Int = lock.withLock { snapshots.size }

    @HighFrequency
    override suspend fun resolve(
        scope: ToolPolicyScope,
        hostedNames: Set<String>,
        isExecuting: Boolean,
    ): ResolvedToolPolicy? {
        val lookup = lock.withLock { ++lookups }
        val engine = scope.engine ?: scope.target?.engine ?: scope.session?.engine
        val catalog = engine?.let { registry.find(it)?.descriptor?.nativeTools }.orEmpty()
        val effective = try {
            val inputs = withTimeoutOrNull(POLICY_MILLIS) {
                providers.value.mapNotNull { it.policy(scope.copy(engine = engine)) } to toggles.get(HarnessNativeTools)
            } ?: error("Policy lookup timed out")
            mergedToolPolicy(catalog, inputs.first, hostedNames, inputs.second && scope.workspace != null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Providers may carry script text or credentials in exception messages and causes.
            log.w(IllegalStateException("Policy lookup failed (${e::class.simpleName.orEmpty()})")) {
                "Tool policy unavailable; execution refused or declarations restored to defaults"
            }
            if (isExecuting) return null
            mergedToolPolicy(catalog, emptyList(), hostedNames, canEnableNative = false)
        }
        return lock.withLock { commit(scope, effective, lookup) }
    }

    /** A lookup that started before the remembered one cannot replace it; equal content keeps its generation. */
    private fun commit(scope: ToolPolicyScope, effective: ResolvedToolPolicy, lookup: Long): ResolvedToolPolicy {
        val previous = snapshots.remove(scope)
        val isSame = previous?.policy?.copy(generation = 0) == effective
        val next = when {
            previous != null && isSame -> previous.copy(lookup = maxOf(previous.lookup, lookup))
            previous != null && previous.lookup > lookup -> previous
            else -> Snapshot(effective.copy(generation = ++generation), lookup)
        }
        snapshots[scope] = next
        if (snapshots.size > MAX_SNAPSHOTS) snapshots.remove(snapshots.keys.first())
        return if (isSame || next.policy.copy(generation = 0) == effective) {
            next.policy
        } else {
            // Outdated content keeps a generation of its own, so it never looks equal to the newer policy.
            effective.copy(generation = ++generation)
        }
    }

    private data class Snapshot(val policy: ResolvedToolPolicy, val lookup: Long)
}

private const val POLICY_MILLIS = 2_000L
private const val MAX_SNAPSHOTS = 256
