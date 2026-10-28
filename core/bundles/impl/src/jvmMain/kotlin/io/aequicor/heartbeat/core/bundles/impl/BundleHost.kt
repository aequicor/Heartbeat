package io.aequicor.heartbeat.core.bundles.impl

import io.aequicor.heartbeat.core.bundles.BindingKey
import io.aequicor.heartbeat.core.bundles.BundleCycleException
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.BundleRegistry
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * Starts bundles and lets them reach each other without compile-time links.
 *
 * Phases: [start] creates every bundle graph (a failure is isolated into [BundleHealth.Failed]) and registers the
 * accessors of its export interfaces; [seal] then validates the wiring and opens resolution. Imports are lazy:
 * a proxy accessor is called only when a dependent binding is built, so references through `Provider` never resolve
 * at graph creation and cycles are detected only when they are real ([BundleCycleException]).
 *
 * Rules checked at [seal]: a non-set key has exactly one exporter; a bundle never exports a key the foundation owns
 * (`FoundationAppBindings`, `FoundationProfileBindings`: it would be a second instance of a stateful singleton);
 * a bundle never both imports and exports the same set (it would count its own contribution twice); a bundle
 * importing a key nobody exports is [BundleHealth.Degraded].
 *
 * An exporter that throws while producing a binding is [BundleHealth.Failed] from then on (at app level; at profile
 * level only that profile loses it, like a failed profile graph): its exports are withdrawn and the consumer gets
 * [BundleUnavailableException] with the failure as the cause. A set collection skips such an exporter. The level is
 * the one the exporter was registered at, not where the consumer looked it up. Only the failing exporter is withdrawn:
 * a consumer that merely passes on an unavailable import, a cycle, or a host condition (bundles still being created,
 * a profile still opening or already closed) is not at fault and stays. Withdrawal stops future resolution only:
 * instances already handed out (for example app singletons of the foundation) stay with their holders.
 *
 * Profiles: [openProfile] repeats the same for the per-profile graphs of the bundles that have one. Profile
 * exports are visible only inside that profile and take precedence over app exports of the same key.
 */
public class BundleHost internal constructor(
    entries: Collection<BundleEntry>,
    private val foundation: () -> Any,
    private val foundationOwned: Set<BindingKey>,
) : BundleRegistry {
    /** Host that refuses bundles exporting what the foundation owns (the keys of `Foundation*Bindings`). */
    public constructor(entries: Collection<BundleEntry>, foundation: () -> Any) :
        this(entries, foundation, foundationOwnedKeys())

    private val log = Log.tag("BundleHost")
    private val entries: List<BundleEntry> = entries.sortedBy { it.id }
    private val healthById = LinkedHashMap<BundleId, BundleHealth>()
    private val graphs = HashMap<BundleId, Any>()
    private val appView = View()
    private val profileViews = HashMap<ScopeHandle, View>()
    private val resolving = ThreadLocal.withInitial { ArrayList<BindingKey>() }
    private val lifecycleLock = Any()

    @Volatile
    private var isSealed = false
    private var isStarting = false
    private var isStarted = false

    init {
        val duplicates = this.entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate bundle ids: $duplicates" }
        require(this.entries.none { it.id == FOUNDATION_ID }) { "Bundle id $FOUNDATION_ID is reserved for the host" }
    }

    override val health: Map<BundleId, BundleHealth> get() = synchronized(healthById) { healthById.toMap() }

    /**
     * The health of the profile graphs of the bundles as [openProfile] last found it (empty until a profile is opened).
     * [health] is the app level only: a bundle whose profile graph fails stays healthy there, so a check that must see
     * the profile graphs (the smoke of a processed train) reads this too. It survives the closing of the profile.
     */
    @Volatile
    public var lastProfileHealth: Map<BundleId, BundleHealth> = emptyMap()
        private set

    /** Graph of a started bundle, for platform code that needs an accessor (`registry.accessor<X>()` successor). */
    public fun graph(id: BundleId): Any? = synchronized(graphs) { graphs[id] }

    /**
     * Creates the bundle graphs in id order over the foundation; a failing bundle does not stop the others.
     * Runs once: a second call would register every export again and fail all bundles, so it is rejected.
     */
    public fun start() {
        synchronized(lifecycleLock) {
            check(!isSealed) { "Host is already sealed" }
            check(!isStarted) { "Bundles are already started" }
            isStarted = true
            isStarting = true
            try {
                for (entry in entries) {
                    runCatching { startBundle(entry) }.onFailure { e ->
                        log.e(e) { "Bundle ${entry.id} failed to start" }
                        markFailed(entry.id, e)
                    }
                }
            } finally {
                isStarting = false
            }
        }
    }

    /** Validates the wiring and opens [resolve] and [collect]. */
    public fun seal() {
        synchronized(lifecycleLock) {
            check(!isSealed) { "Host is already sealed" }
            for (entry in entries) {
                if (health[entry.id] != BundleHealth.Healthy) continue
                runCatching { checkSetOwnership(entry, entry.importsType, entry.exportTypes) }.onFailure { e ->
                    log.e(e) { "Bundle ${entry.id} violates the wiring rules" }
                    markFailed(entry.id, e)
                }
            }
            for (entry in entries) {
                if (health[entry.id] == BundleHealth.Healthy) {
                    markMissingImports(entry.id, appView, entry.importsType)?.let { degraded ->
                        synchronized(healthById) { healthById[entry.id] = degraded }
                    }
                }
            }
            isSealed = true
        }
        log.i { "Bundles sealed: ${health.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }}" }
    }

    /**
     * [start] and [seal] once; later calls (and concurrent ones) only wait for the first. Resolving an import starts
     * the bundles on demand, so every application graph works without calling this. Starting is not re-entrant:
     * a bundle graph must not resolve imports while it is being created.
     */
    public fun ensureStarted() {
        synchronized(lifecycleLock) {
            if (isSealed) return
            if (isStarting) {
                throw HostNotReadyException(
                    "Bundles are being created; imports cannot be resolved while graphs are created",
                )
            }
            if (!isStarted) start()
            seal()
        }
    }

    /**
     * Creates the per-profile graphs of the bundles on top of the [foundation] profile bindings (the host passes
     * `FoundationProfileBindings` of the profile) and ties their
     * lifetime to [scope]. A bundle that failed at app level gets no profile graph. Returns the health by bundle.
     */
    public fun openProfile(foundation: Any, scope: ScopeHandle): Map<BundleId, BundleHealth> {
        ensureStarted()
        val view = View().apply { isOpen = false }
        synchronized(profileViews) { profileViews[scope] = view }
        val profileHealth = LinkedHashMap<BundleId, BundleHealth>()
        val appHealth = health
        val members = entries.filter { it.hasProfileGraph && appHealth[it.id] != null }
        for (entry in members) {
            if (appHealth[entry.id] is BundleHealth.Failed) continue
            val failure = runCatching { startProfileBundle(entry, foundation, view) }.exceptionOrNull()
            profileHealth[entry.id] = if (failure == null) {
                BundleHealth.Healthy
            } else {
                log.e(failure) { "Bundle ${entry.id} failed to create its profile graph" }
                removeOwnedBy(entry.id, view)
                BundleHealth.Failed(failure)
            }
        }
        for (entry in members) {
            if (profileHealth[entry.id] != BundleHealth.Healthy) continue
            val failure = runCatching {
                checkSetOwnership(entry, entry.profileImportsType, entry.profileExportTypes)
            }.exceptionOrNull()
            if (failure != null) {
                log.e(failure) { "Bundle ${entry.id} violates the profile wiring rules" }
                removeOwnedBy(entry.id, view)
                profileHealth[entry.id] = BundleHealth.Failed(failure)
            }
        }
        for (entry in members) {
            if (profileHealth[entry.id] == BundleHealth.Healthy) {
                markMissingImports(entry.id, view, entry.profileImportsType)?.let { profileHealth[entry.id] = it }
            }
        }
        view.isOpen = true
        scope.onClose {
            view.close()
            synchronized(profileViews) { profileViews.remove(scope) }
        }
        val summary = profileHealth.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }
        log.i { "Profile graphs of bundles: $summary" }
        lastProfileHealth = profileHealth.toMap()
        return profileHealth
    }

    /**
     * Cross-bundle accessors of the [type] interface for the foundation itself (it is not a bundle, so it keeps its
     * own list of what it takes from bundles). Resolved lazily like the imports of a bundle.
     */
    public fun <T : Any> foundationImports(type: KClass<T>): T =
        type.java.cast(importsProxy(FOUNDATION_ID, type.java, null))

    /**
     * Like [foundationImports], for the profile graph of the foundation: accessors see the exports of the bundles'
     * profile graphs opened for [scope] (the profile scope the foundation graph was created with), then the app
     * exports. Resolved lazily; a profile whose bundle graphs were not opened (or already closed) reports
     * [BundleUnavailableException], so the foundation can fall back like it does for app imports.
     */
    public fun <T : Any> foundationProfileImports(type: KClass<T>, scope: ScopeHandle): T =
        type.java.cast(importsProxy(FOUNDATION_ID, type.java, null) { profileViewOf(scope) })

    private fun profileViewOf(scope: ScopeHandle): View? = synchronized(profileViews) { profileViews[scope] }

    override fun resolve(key: BindingKey): Any = resolveFor(key, null, null)

    override fun collect(key: BindingKey, excluding: BundleId?): Set<Any> = collectFor(key, excluding, null)

    private fun startBundle(entry: BundleEntry) {
        val imports: Any = entry.importsType?.let { type -> importsProxy(entry.id, type.java, null) } ?: Unit
        val graph = entry.createGraph(foundation(), imports)
        val count = register(entry.id, graph, entry.exportTypes, appView, parent = null)
        synchronized(graphs) { graphs[entry.id] = graph }
        synchronized(healthById) { healthById[entry.id] = BundleHealth.Healthy }
        log.i { "Bundle ${entry.id} started: $count exports" }
    }

    private fun startProfileBundle(entry: BundleEntry, foundation: Any, view: View) {
        val appGraph = checkNotNull(graph(entry.id)) { "No app graph of ${entry.id}" }
        val imports: Any = entry.profileImportsType?.let { type -> importsProxy(entry.id, type.java, view) } ?: Unit
        val graph = entry.createProfileGraph(appGraph, foundation, imports)
        register(entry.id, graph, entry.profileExportTypes, view, parent = appView)
    }

    /** Registers the accessors of [types] implemented by [graph]; a single-binding clash fails the whole bundle. */
    private fun register(id: BundleId, graph: Any, types: List<KClass<*>>, view: View, parent: View?): Int {
        val own = types.flatMap { type ->
            accessorsOf(type.java).map { method ->
                val key = bindingKeyOf(method.genericReturnType)
                Exporter(id, key, method, graph, profile = view.takeIf { parent != null })
            }
        }
        val duplicated = own.filter { it.key in foundationOwned }
        check(duplicated.isEmpty()) {
            "$id exports ${duplicated.map { it.key }}: owned by the foundation, a second instance of a service"
        }
        synchronized(view.exporters) {
            fun existing(key: BindingKey) = view.exporters[key].orEmpty() + parent?.exporters?.get(key).orEmpty()
            val conflict = own.firstOrNull { new -> !new.method.isSetAccessor() && existing(new.key).isNotEmpty() }
            check(conflict == null) {
                "$id exports ${conflict?.key}, already exported by " +
                    existing(conflict!!.key).joinToString { it.owner.value }
            }
            own.forEach { view.exporters.getOrPut(it.key) { ArrayList() }.add(it) }
        }
        return own.size
    }

    private fun checkSetOwnership(entry: BundleEntry, importsType: KClass<*>?, exportTypes: List<KClass<*>>) {
        val imported = importsType?.let { accessorsOf(it.java) }.orEmpty()
        val exportedSets = exportTypes.flatMap { accessorsOf(it.java) }.filter { it.isSetAccessor() }
            .map { bindingKeyOf(it.genericReturnType) }.toSet()
        val doubled = imported.filter { it.isSetAccessor() }.map { bindingKeyOf(it.genericReturnType) }
            .filter { it in exportedSets }
        check(doubled.isEmpty()) { "${entry.id} both imports and exports sets $doubled" }
    }

    private fun markMissingImports(id: BundleId, view: View, importsType: KClass<*>?): BundleHealth? {
        val imported = importsType?.let { accessorsOf(it.java) }.orEmpty()
        val missing = imported.filterNot { it.isSetAccessor() }.map { bindingKeyOf(it.genericReturnType) }
            .filter { view.find(it, appView).isEmpty() }
        if (missing.isEmpty()) return null
        log.w { "Bundle $id is degraded, nobody exports $missing" }
        return BundleHealth.Degraded(missing)
    }

    private fun importsProxy(owner: BundleId, type: Class<*>, view: View?, viewOf: (() -> View?)? = null): Any =
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { proxy, method, _ ->
            when (method.name) {
                "toString" -> "imports of $owner"

                "hashCode" -> System.identityHashCode(proxy)

                "equals" -> false

                else -> {
                    val key = bindingKeyOf(method.genericReturnType)
                    val target = if (viewOf == null) view else viewOf() ?: throw BundleUnavailableException(key, owner)
                    if (method.isSetAccessor()) collectFor(key, owner, target) else resolveFor(key, owner, target)
                }
            }
        }

    private fun resolveFor(key: BindingKey, requestedBy: BundleId?, view: View?): Any {
        ensureReady(key, requestedBy, view)
        val exporter = (view ?: appView).find(key, appView).firstOrNull()
            ?: throw BundleUnavailableException(key, requestedBy)
        return read(exporter, requestedBy)
    }

    private fun collectFor(key: BindingKey, excluding: BundleId?, view: View?): Set<Any> {
        ensureReady(key, excluding, view)
        val found = view?.snapshot(key).orEmpty() + appView.snapshot(key)
        return found.filter { it.owner != excluding }
            .flatMapTo(LinkedHashSet()) { exporter ->
                try {
                    (read(exporter, excluding) as Set<*>).filterNotNull()
                } catch (e: BundleUnavailableException) {
                    // A withdrawn exporter is already logged with its own failure by [withdraw].
                    if (exporter.isRegistered()) log.w(e) { "Set ${exporter.key} misses the part of ${exporter.owner}" }
                    emptyList()
                }
            }
    }

    /**
     * Host conditions are not the fault of the exporter whose binding asks: they surface as
     * [BundleUnavailableException], which [read] passes on without withdrawing anybody.
     */
    private fun ensureReady(key: BindingKey, requestedBy: BundleId?, view: View?) {
        try {
            ensureStarted()
        } catch (e: HostNotReadyException) {
            throw BundleUnavailableException(key, requestedBy, e)
        }
        if (view != null && !view.isOpen) {
            val reason = HostNotReadyException(
                "The profile graphs of the bundles are still being created or are closed",
            )
            throw BundleUnavailableException(key, requestedBy, reason)
        }
    }

    /**
     * Reads [exporter]; its own failure withdraws it (see the class docs) and surfaces as
     * [BundleUnavailableException]. A cycle propagates; an unavailable import of the exporter (including a host
     * condition) does not fail the exporter.
     */
    private fun read(exporter: Exporter, requestedBy: BundleId?): Any = try {
        guarded(exporter.key) { exporter.read() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: BundleCycleException) {
        throw e
    } catch (e: BundleUnavailableException) {
        throw BundleUnavailableException(exporter.key, requestedBy, e)
    } catch (e: Exception) {
        withdraw(exporter, e)
        throw BundleUnavailableException(exporter.key, requestedBy, e)
    } catch (e: LinkageError) {
        withdraw(exporter, e)
        throw BundleUnavailableException(exporter.key, requestedBy, e)
    }

    /** Whether [this] is still exported at its level, that is, not withdrawn (or its profile closed). */
    private fun Exporter.isRegistered(): Boolean = (profile ?: appView).snapshot(key).any { it === this }

    /** Withdraws [exporter] at the level it was registered at: its profile only, or the whole app. */
    private fun withdraw(exporter: Exporter, cause: Throwable) {
        val profile = exporter.profile
        if (profile != null) {
            log.e(cause) { "Bundle ${exporter.owner} failed to export ${exporter.key}; it leaves this profile" }
            removeOwnedBy(exporter.owner, profile)
        } else {
            log.e(cause) { "Bundle ${exporter.owner} failed to export ${exporter.key}; it is failed" }
            markFailed(exporter.owner, cause)
            synchronized(profileViews) { profileViews.values.toList() }.forEach { removeOwnedBy(exporter.owner, it) }
        }
    }

    private inline fun <T> guarded(key: BindingKey, block: () -> T): T {
        val stack = resolving.get()
        if (key in stack) throw BundleCycleException(stack + key)
        stack.add(key)
        try {
            return block()
        } finally {
            stack.removeAt(stack.lastIndex)
        }
    }

    private fun markFailed(id: BundleId, cause: Throwable) {
        synchronized(healthById) { healthById[id] = BundleHealth.Failed(cause) }
        removeOwnedBy(id, appView)
    }

    private fun removeOwnedBy(id: BundleId, view: View) {
        synchronized(view.exporters) { view.exporters.values.forEach { list -> list.removeAll { it.owner == id } } }
    }

    private companion object {
        val FOUNDATION_ID = BundleId("foundation")
    }

    /** Exporters visible at one level: the app, or one profile (which is consulted before the app). */
    private class View {
        val exporters = HashMap<BindingKey, MutableList<Exporter>>()

        @Volatile
        var isOpen: Boolean = true

        fun snapshot(key: BindingKey): List<Exporter> = synchronized(exporters) { exporters[key].orEmpty().toList() }

        fun find(key: BindingKey, parent: View): List<Exporter> =
            synchronized(exporters) { exporters[key].orEmpty().toList() }
                .ifEmpty { synchronized(parent.exporters) { parent.exporters[key].orEmpty().toList() } }

        fun close() {
            isOpen = false
            synchronized(exporters) { exporters.clear() }
        }
    }

    /** A host condition (not a bundle failure) that keeps a binding from resolving now. */
    private class HostNotReadyException(message: String) : IllegalStateException(message)

    /** [profile]: the profile view the exporter was registered in, `null` for an app-level exporter. */
    private class Exporter(
        val owner: BundleId,
        val key: BindingKey,
        val method: Method,
        val graph: Any,
        val profile: View?,
    ) {
        fun read(): Any = try {
            checkNotNull(method.invoke(graph)) { "${owner.value} exported null for $key" }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
