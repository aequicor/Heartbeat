package io.aequicor.heartbeat.feature.worktreemode.impl.data

import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeBuildKind
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildQueuePolicyTest {
    private val budget = BuildBudget(units = 4, memoryMb = Long.MAX_VALUE)

    @Test
    fun `auto budget follows processors and keeps a memory reserve`() {
        assertEquals(BuildBudget(5, 24_576), BuildBudget.auto(processors = 10, memoryMb = 32_768))
        assertEquals(BuildBudget(2, 4_096), BuildBudget.auto(processors = 2, memoryMb = 8_192))
        assertEquals(2, BuildBudget.auto(processors = 10, memoryMb = null).parallelBuilds)
        assertEquals(BuildBudget(6, Long.MAX_VALUE, maxConcurrentBuilds = 3), BuildBudget.parallel(3))
        assertEquals(BuildBudget.MAX_PARALLEL_BUILDS, BuildBudget.parallel(100).parallelBuilds)
    }

    @Test
    fun `the automatic standard build estimate accounts for memory and admits an oversized build alone`() {
        val automatic = BuildBudget.auto(processors = 32, memoryMb = 8_192)
        val waiting = List(8) { build("b$it", "p$it") }
        val admission = admitBuilds(waiting, running = emptyList(), automatic)
        assertEquals(1, automatic.parallelBuilds)
        assertEquals(automatic.parallelBuilds, admission.admitted.size)

        val belowOneBuild = BuildBudget.auto(processors = 32, memoryMb = 4_096)
        assertEquals(1, belowOneBuild.parallelBuilds)
        assertEquals(1, admitBuilds(waiting, running = emptyList(), belowOneBuild).admitted.size)
    }

    @Test
    fun `a fixed limit caps light builds across projects`() {
        val admission = admitBuilds(
            List(3) { build("b$it", "p$it", WorktreeBuildKind.Check) },
            running = emptyList(),
            BuildBudget.parallel(1),
        )
        assertEquals(setOf("b0"), admission.admitted.keys)
        assertEquals(mapOf("b1" to 1, "b2" to 2), admission.positions)
    }

    @Test
    fun `a fixed limit counts running builds and waits when the limit is lowered`() {
        val waiting = listOf(build("next", "p", WorktreeBuildKind.Check))
        val running = listOf(
            running("first", "q", WorktreeBuildKind.Check),
            running("second", "r", WorktreeBuildKind.Check),
        )
        assertEquals(emptyMap(), admitBuilds(waiting, running, BuildBudget.parallel(2)).admitted)
        assertEquals(emptyMap(), admitBuilds(waiting, running, BuildBudget.parallel(1)).admitted)
        assertEquals(setOf("next"), admitBuilds(waiting, running.take(1), BuildBudget.parallel(2)).admitted.keys)
    }

    @Test
    fun `hold leases neither consume nor wait for the fixed build count`() {
        val admission = admitBuilds(
            listOf(
                build("first", "p", WorktreeBuildKind.Check),
                build("next", "q", WorktreeBuildKind.Check),
                QueuedBuild("hold", "r", BuildCost.None, setOf("repo-r")),
            ),
            running = listOf(RunningBuild("held", "s", BuildCost.None, setOf("repo-s"))),
            BuildBudget.parallel(1),
        )
        assertEquals(setOf("first", "hold"), admission.admitted.keys)
        assertEquals(mapOf("next" to 1), admission.positions)
    }

    @Test
    fun `builds of one project start in parallel up to the weighted budget`() {
        val admission = admitBuilds(
            listOf(build("a", "p"), build("b", "p"), build("c", "p")),
            running = emptyList(),
            budget,
        )
        assertEquals(setOf("a", "b"), admission.admitted.keys)
        assertEquals(mapOf("c" to 1), admission.positions)
    }

    @Test
    fun `projects are served round robin with the less loaded project first`() {
        val admission = admitBuilds(
            listOf(build("a1", "a"), build("a2", "a"), build("b1", "b")),
            running = listOf(running("busy", "a", WorktreeBuildKind.Check)),
            BuildBudget(units = 5, memoryMb = Long.MAX_VALUE),
        )
        assertEquals(listOf("b1", "a1"), admission.admitted.keys.toList())
        assertEquals(mapOf("a2" to 1), admission.positions)
    }

    @Test
    fun `a heavy build blocked by capacity is not overtaken by lighter later builds`() {
        val admission = admitBuilds(
            listOf(build("native", "p", WorktreeBuildKind.Native), build("check", "q", WorktreeBuildKind.Check)),
            running = listOf(running("compile", "r")),
            budget,
        )
        assertEquals(emptyMap(), admission.admitted)
        assertEquals(mapOf("native" to 1, "check" to 2), admission.positions)
    }

    @Test
    fun `a build larger than the whole budget runs alone on an idle machine`() {
        val admission = admitBuilds(
            listOf(build("native", "p", WorktreeBuildKind.Native), build("check", "p", WorktreeBuildKind.Check)),
            running = emptyList(),
            BuildBudget(units = 2, memoryMb = 1_000),
        )
        assertEquals(setOf("native"), admission.admitted.keys)
    }

    @Test
    fun `exclusive resources keep FIFO order without blocking unrelated builds`() {
        val admission = admitBuilds(
            listOf(
                build("first", "p", resources = setOf("cache")),
                build("second", "p", resources = setOf("cache")),
                build("other", "q"),
            ),
            running = emptyList(),
            budget,
        )
        assertEquals(setOf("first", "other"), admission.admitted.keys)
    }

    @Test
    fun `slots are assigned with affinity and a full pool waits`() {
        val pool = SlotDemand("repo", limit = 2, preferred = 1)
        val admission = admitBuilds(
            listOf(
                build("a", "p", slot = pool),
                build("b", "p", slot = pool.copy(preferred = 1)),
                build("c", "p", slot = pool.copy(preferred = null)),
            ),
            running = emptyList(),
            BuildBudget(units = 8, memoryMb = Long.MAX_VALUE),
        )
        assertEquals(mapOf("a" to 1, "b" to 0), admission.admitted)
        assertEquals(mapOf("c" to 1), admission.positions)
    }

    @Test
    fun `hold leases need no capacity even above a restored budget`() {
        val admission = admitBuilds(
            listOf(QueuedBuild("hold", "p", BuildCost.None, setOf("repo"))),
            running = listOf(running("x", "q", WorktreeBuildKind.Native), running("y", "q", WorktreeBuildKind.Native)),
            budget,
        )
        assertEquals(setOf("hold"), admission.admitted.keys)
    }

    @Test
    fun `a hold lease is not delayed by another project's build waiting for capacity`() {
        val admission = admitBuilds(
            listOf(
                build("native", "p", WorktreeBuildKind.Native),
                QueuedBuild("hold", "q", BuildCost.None, setOf("repo-q")),
            ),
            running = listOf(running("compile", "r")),
            budget,
        )
        assertEquals(setOf("hold"), admission.admitted.keys)
        assertEquals(mapOf("native" to 1), admission.positions)
    }

    @Test
    fun `the unlimited budget keeps only exclusive resources as limits`() {
        val admission = admitBuilds(
            List(20) { build("b$it", "p$it", WorktreeBuildKind.Native) },
            running = emptyList(),
            BuildBudget.Unlimited,
        )
        assertEquals(20, admission.admitted.size)
        assertEquals(BuildBudget.MAX_PARALLEL_BUILDS, BuildBudget.Unlimited.parallelBuilds)
    }

    private fun build(
        id: String,
        project: String,
        kind: WorktreeBuildKind = WorktreeBuildKind.Compile,
        resources: Set<String> = emptySet(),
        slot: SlotDemand? = null,
    ) = QueuedBuild(id, project, kind.cost(), resources, slot)

    private fun running(id: String, project: String, kind: WorktreeBuildKind = WorktreeBuildKind.Compile) =
        RunningBuild(id, project, kind.cost(), emptySet())
}
