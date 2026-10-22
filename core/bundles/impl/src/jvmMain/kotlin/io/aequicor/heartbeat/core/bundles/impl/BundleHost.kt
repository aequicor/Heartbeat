package io.aequicor.heartbeat.core.bundles.impl

import io.aequicor.heartbeat.core.bundles.BindingKey
import io.aequicor.heartbeat.core.bundles.BundleCycleException
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.BundleRegistry
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.logging.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Starts bundles and lets them reach each other without compile-time links.
 *
 * Phases: [start] creates every bundle graph (a failure is isolated into [BundleHealth.Failed]) and registers the
 * accessors of its export interfaces; [seal] then validates the wiring and opens resolution. Imports are lazy:
 * a proxy accessor is called only when a dependent binding is built, so references through `Provider` never resolve
 * at graph creation and cycles are detected only when they are real ([BundleCycleException]).
 *
 * Rules checked at [seal]: a non-set key has exactly one exporter; a bundle never both imports and exports the
 * same set (it would count its own contribution twice); a bundle importing a key nobody exports is
 * [BundleHealth.Degraded].
 */
public class BundleHost(entries: Collection<BundleEntry>) : BundleRegistry {
    private val log = Log.tag("BundleHost")
    private val entries: List<BundleEntry> = entries.sortedBy { it.id }
    private val healthById = LinkedHashMap<BundleId, BundleHealth>()
    private val graphs = HashMap<BundleId, Any>()
    private val exporters = HashMap<BindingKey, MutableList<Exporter>>()
    private val resolving = ThreadLocal.withInitial { ArrayList<BindingKey>() }

    @Volatile
    private var isSealed = false

    init {
        val duplicates = this.entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate bundle ids: $duplicates" }
    }

    override val health: Map<BundleId, BundleHealth> get() = synchronized(healthById) { healthById.toMap() }

    /** Graph of a started bundle, for platform code that needs an accessor (`registry.accessor<X>()` successor). */
    public fun graph(id: BundleId): Any? = synchronized(graphs) { graphs[id] }

    /** Creates the bundle graphs in id order; a failing bundle does not stop the others. */
    public fun start() {
        check(!isSealed) { "Host is already sealed" }
        for (entry in entries) {
            runCatching { startBundle(entry) }.onFailure { e ->
                log.e(e) { "Bundle ${entry.id} failed to start" }
                markFailed(entry.id, e)
            }
        }
    }

    /** Validates the wiring and opens [resolve] and [collect]. */
    public fun seal() {
        check(!isSealed) { "Host is already sealed" }
        for (entry in entries) {
            if (healthById[entry.id] != BundleHealth.Healthy) continue
            runCatching { checkSetOwnership(entry) }.onFailure { e ->
                log.e(e) { "Bundle ${entry.id} violates the wiring rules" }
                markFailed(entry.id, e)
            }
        }
        for (entry in entries) {
            if (healthById[entry.id] == BundleHealth.Healthy) markMissingImports(entry)
        }
        isSealed = true
        log.i { "Bundles sealed: ${health.entries.joinToString { "${it.key}=${it.value::class.simpleName}" }}" }
    }

    override fun resolve(key: BindingKey): Any = resolveFor(key, null)

    override fun collect(key: BindingKey, excluding: BundleId?): Set<Any> = collectFor(key, excluding)

    private fun startBundle(entry: BundleEntry) {
        val imports: Any = entry.importsType?.let { type -> importsProxy(entry.id, type.java) } ?: Unit
        val graph = entry.createGraph(imports)
        val own = entry.exportTypes.flatMap { type ->
            accessorsOf(
                type.java,
            ).map { method -> Exporter(entry.id, bindingKeyOf(method.genericReturnType), method, graph) }
        }
        synchronized(exporters) {
            val conflict = own.firstOrNull { new ->
                !new.method.isSetAccessor() && exporters[new.key].orEmpty().isNotEmpty()
            }
            check(conflict == null) {
                "${entry.id} exports ${conflict?.key}, already exported by " +
                    exporters[conflict?.key].orEmpty().joinToString { it.owner.value }
            }
            own.forEach { exporters.getOrPut(it.key) { ArrayList() }.add(it) }
        }
        synchronized(graphs) { graphs[entry.id] = graph }
        synchronized(healthById) { healthById[entry.id] = BundleHealth.Healthy }
        log.i { "Bundle ${entry.id} started: ${own.size} exports" }
    }

    private fun checkSetOwnership(entry: BundleEntry) {
        val imported = entry.importsType?.let { accessorsOf(it.java) }.orEmpty()
        val exportedSets = exporters.values.flatten().filter { it.owner == entry.id && it.method.isSetAccessor() }
            .map { it.key }.toSet()
        val doubled = imported.filter { it.isSetAccessor() }.map { bindingKeyOf(it.genericReturnType) }
            .filter { it in exportedSets }
        check(doubled.isEmpty()) { "${entry.id} both imports and exports sets $doubled" }
    }

    private fun markMissingImports(entry: BundleEntry) {
        val imported = entry.importsType?.let { accessorsOf(it.java) }.orEmpty()
        val missing = imported.filterNot { it.isSetAccessor() }.map { bindingKeyOf(it.genericReturnType) }
            .filter { exporters[it].orEmpty().isEmpty() }
        if (missing.isNotEmpty()) {
            synchronized(healthById) { healthById[entry.id] = BundleHealth.Degraded(missing) }
            log.w { "Bundle ${entry.id} is degraded, nobody exports $missing" }
        }
    }

    private fun importsProxy(owner: BundleId, type: Class<*>): Any = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { proxy, method, _ ->
        when (method.name) {
            "toString" -> "imports of $owner"

            "hashCode" -> System.identityHashCode(proxy)

            "equals" -> false

            else -> {
                val key = bindingKeyOf(method.genericReturnType)
                if (method.isSetAccessor()) collectFor(key, owner) else resolveFor(key, owner)
            }
        }
    }

    private fun resolveFor(key: BindingKey, requestedBy: BundleId?): Any {
        check(isSealed) { "Bundle registry is not sealed yet; imports cannot be resolved while graphs are created" }
        val exporter = exporters[key].orEmpty().firstOrNull() ?: throw BundleUnavailableException(key, requestedBy)
        return guarded(key) { exporter.read() }
    }

    private fun collectFor(key: BindingKey, excluding: BundleId?): Set<Any> {
        check(isSealed) { "Bundle registry is not sealed yet; imports cannot be resolved while graphs are created" }
        return exporters[key].orEmpty().filter { it.owner != excluding }
            .flatMapTo(LinkedHashSet()) { exporter ->
                guarded(key) { exporter.read() as Set<*> }.filterNotNull()
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
        synchronized(exporters) { exporters.values.forEach { list -> list.removeAll { it.owner == id } } }
    }

    private class Exporter(val owner: BundleId, val key: BindingKey, val method: Method, val graph: Any) {
        fun read(): Any = try {
            checkNotNull(method.invoke(graph)) { "${owner.value} exported null for $key" }
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
