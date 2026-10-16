package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildKind

/** Estimated load of one build: CPU units and resident memory of its process tree. */
internal data class BuildCost(val units: Int, val memoryMb: Long) {
    operator fun plus(other: BuildCost): BuildCost = BuildCost(units + other.units, memoryMb + other.memoryMb)

    companion object {
        /** Hold leases serialize agents without running a build and consume no capacity. */
        val None: BuildCost = BuildCost(0, 0)
    }
}

/** Relative weights; a [WorktreeBuildKind.Compile] is one standard build of [BuildBudget.UNITS_PER_BUILD] units. */
internal fun WorktreeBuildKind.cost(): BuildCost = when (this) {
    WorktreeBuildKind.Check -> BuildCost(units = 1, memoryMb = 1_024)
    WorktreeBuildKind.Compile -> BuildCost(units = 2, memoryMb = 3_072)
    WorktreeBuildKind.Test -> BuildCost(units = 2, memoryMb = 3_072)
    WorktreeBuildKind.Assemble -> BuildCost(units = 3, memoryMb = 4_096)
    WorktreeBuildKind.Native -> BuildCost(units = 4, memoryMb = 6_144)
}

/**
 * Machine-wide capacity shared by the builds of every project and profile. Resource weights apply to every build;
 * [maxConcurrentBuilds] additionally caps the count of actual builds when the user chooses a fixed limit.
 * Hold leases do not run a build and consume neither resources nor a place under that limit.
 */
internal data class BuildBudget(val units: Int, val memoryMb: Long, val maxConcurrentBuilds: Int = Int.MAX_VALUE) {
    /**
     * Whole standard builds that fit both CPU and memory; also bounds warm slots of one repository. At least one
     * is offered because a single oversized build can run on an idle machine.
     */
    val parallelBuilds: Int
        get() = minOf(
            (units / UNITS_PER_BUILD).toLong(),
            memoryMb / WorktreeBuildKind.Compile.cost().memoryMb,
            maxConcurrentBuilds.toLong(),
        ).coerceIn(1L, MAX_PARALLEL_BUILDS.toLong()).toInt()

    companion object {
        const val UNITS_PER_BUILD = 2
        const val MAX_PARALLEL_BUILDS = 16
        private const val MIN_RESERVED_MEMORY_MB = 4_096L
        private const val RESERVED_MEMORY_SHARE = 4

        /**
         * Half of the logical processors (builds parallelize internally) and the physical memory left after a
         * reserve for the desktop session. An unknown memory size never blocks admission.
         */
        fun auto(processors: Int, memoryMb: Long?): BuildBudget {
            val units = (processors / 2).coerceIn(UNITS_PER_BUILD, MAX_PARALLEL_BUILDS * UNITS_PER_BUILD)
            val memory = memoryMb?.let {
                (it - maxOf(MIN_RESERVED_MEMORY_MB, it / RESERVED_MEMORY_SHARE)).coerceAtLeast(0)
            } ?: Long.MAX_VALUE
            return BuildBudget(units, memory)
        }

        /** Without the build queue only exclusive resources limit admission, as before it existed. */
        val Unlimited: BuildBudget = BuildBudget(Int.MAX_VALUE / 2, Long.MAX_VALUE / 2)

        /**
         * An explicit limit caps the actual build count as well as the equivalent standard-build resource budget.
         * Heavy builds can use more of that budget; light builds never exceed the count. Memory is the user's choice.
         */
        fun parallel(builds: Int): BuildBudget {
            val limit = builds.coerceIn(1, MAX_PARALLEL_BUILDS)
            return BuildBudget(limit * UNITS_PER_BUILD, Long.MAX_VALUE, maxConcurrentBuilds = limit)
        }
    }
}

/** Warm slots of one repository; each keeps its own build outputs on disk. */
internal const val MAX_SLOTS_PER_REPOSITORY = 4

/** Slots a repository may use under this budget, and keeps on disk. */
internal val BuildBudget.slotsPerRepository: Int get() = minOf(parallelBuilds, MAX_SLOTS_PER_REPOSITORY)

/** A build slot pool of one repository; slots are numbered from zero up to [limit]. */
internal data class SlotDemand(val pool: String, val limit: Int, val preferred: Int? = null)

/** Waiting build in arrival order; [project] groups builds for fairness, [resources] are exclusive locks. */
internal data class QueuedBuild(
    val id: String,
    val project: String,
    val cost: BuildCost,
    val resources: Set<String>,
    val slot: SlotDemand? = null,
)

/** Accepted build still holding capacity, its locks and possibly a slot. */
internal data class RunningBuild(
    val id: String,
    val project: String,
    val cost: BuildCost,
    val resources: Set<String>,
    val slotPool: String? = null,
    val slot: Int? = null,
)

/** Builds to start now with their assigned slots, and 1-based positions of the builds that keep waiting. */
internal data class BuildAdmission(val admitted: Map<String, Int?>, val positions: Map<String, Int>)

/**
 * Admission is FIFO within a project and round-robin across projects, with projects that currently run fewer builds
 * served first. A build conflicting with an exclusive resource of a running or earlier build, or without a free slot,
 * waits without blocking others. A build that exceeds the remaining budget stops admission entirely, so lighter later
 * builds cannot starve a heavy one; a single build larger than the whole budget still runs alone.
 */
internal fun admitBuilds(
    waiting: List<QueuedBuild>,
    running: List<RunningBuild>,
    budget: BuildBudget,
): BuildAdmission {
    val round = AdmissionRound(running, budget)
    fairOrder(waiting, running).forEach(round::offer)
    val positions = fairOrder(waiting.filter { it.id !in round.admitted }, running)
        .mapIndexed { index, build -> build.id to index + 1 }.toMap()
    return BuildAdmission(round.admitted, positions)
}

/** Mutable accounting of one admission pass over the fair order. */
private class AdmissionRound(running: List<RunningBuild>, private val budget: BuildBudget) {
    val admitted = linkedMapOf<String, Int?>()
    private var used = running.fold(BuildCost.None) { total, build -> total + build.cost }
    private var activeBuilds = running.count { it.cost != BuildCost.None }
    private val owned = running.flatMap { it.resources }.toMutableSet()
    private val occupied = running.mapNotNull { build ->
        val pool = build.slotPool ?: return@mapNotNull null
        build.slot?.let { pool to it }
    }.toMutableSet()
    private val preceding = mutableSetOf<String>()
    private var isCapacityBlocked = false

    fun offer(build: QueuedBuild) {
        val isConflicting = build.resources.any { it in owned || it in preceding }
        preceding += build.resources
        // Hold leases consume no capacity, so a heavy build waiting for capacity never delays them.
        if (isConflicting || (isCapacityBlocked && build.cost != BuildCost.None)) return
        val demand = build.slot
        val slot = demand?.let { freeSlot(it, occupied) }
        when {
            demand != null && slot == null -> Unit

            !fits(build.cost) -> isCapacityBlocked = true

            else -> {
                admitted[build.id] = slot
                owned += build.resources
                if (demand != null && slot != null) occupied += demand.pool to slot
                used += build.cost
                if (build.cost != BuildCost.None) activeBuilds++
            }
        }
    }

    private fun fits(cost: BuildCost): Boolean {
        if (cost == BuildCost.None) return true
        if (activeBuilds >= budget.maxConcurrentBuilds) return false
        val total = used + cost
        return activeBuilds == 0 || (total.units <= budget.units && total.memoryMb <= budget.memoryMb)
    }
}

private fun fairOrder(waiting: List<QueuedBuild>, running: List<RunningBuild>): List<QueuedBuild> {
    val load = running.groupingBy { it.project }.eachCount()
    val groups = waiting.groupBy { it.project }.values
        .sortedWith(compareBy({ load[it.first().project] ?: 0 }, { waiting.indexOf(it.first()) }))
    val depth = groups.maxOfOrNull { it.size } ?: 0
    return (0 until depth).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }
}

private fun freeSlot(demand: SlotDemand, occupied: Set<Pair<String, Int>>): Int? {
    val preferred = demand.preferred?.takeIf { it in 0 until demand.limit && (demand.pool to it) !in occupied }
    return preferred ?: (0 until demand.limit).firstOrNull { (demand.pool to it) !in occupied }
}
