package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyUnavailable
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolSwitch
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

    /** Profiles without context optimization leave rebuilt prompts verbatim. */
    @Multibinds(allowEmpty = true)
    public fun contextPolicyProviders(): Set<ContextPolicyProvider>

    /** Profiles without context optimization keep tool output whole. */
    @Multibinds(allowEmpty = true)
    public fun toolOutputOffloads(): Set<ToolOutputOffload>
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
                val selected = providers.value.filter { it.isIsolation }
                val policies = selected.mapNotNull { it.policy(scope.copy(engine = engine)) }
                val enabled = ToolPolicy(native = catalog.filter { it.isGated }.associate { it.name to ToolSwitch.On })
                (policies + enabled) to true
            } ?: error("Policy lookup timed out")
            mergedToolPolicy(catalog, inputs.first, hostedNames, inputs.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isReportedUnavailability()) {
                // Its provider already reported the unavailability: failing closed stays, the report does not repeat.
                log.v { "tool policy source still unavailable; execution refused or declarations restored to defaults" }
            } else {
                // Providers may carry script text or credentials in exception messages and causes.
                log.w(IllegalStateException("Policy lookup failed (${e::class.simpleName.orEmpty()})")) {
                    "Tool policy unavailable; execution refused or declarations restored to defaults"
                }
            }
            null
        } ?: if (isExecuting) return null else mergedToolPolicy(catalog, emptyList(), hostedNames, false)
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

private fun Exception.isReportedUnavailability(): Boolean = this is ToolPolicyUnavailable

private const val POLICY_MILLIS = 2_000L
private const val MAX_SNAPSHOTS = 256
