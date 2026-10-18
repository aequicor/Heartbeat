package io.aequicor.heartbeat.feature.planningmode.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent.Internal
import io.aequicor.heartbeat.feature.planningmode.api.PlanningIntent.Public
import io.aequicor.heartbeat.feature.planningmode.api.PlanningState.Ready
import kotlin.test.Test
import kotlin.time.Instant

class PlanningMachineTest {
    private val spec = PlanningMachineSpec

    @Test
    fun `start loads plans and a failure can be retried`() {
        spec.assertTransition(PlanningState.Idle, Public.Start, PlanningState.Loading, listOf(PlanningEffect.Load))
        spec.assertTransition(PlanningState.Loading, Internal.LoadFailed, PlanningState.Failed)
        spec.assertTransition(PlanningState.Failed, Public.Retry, PlanningState.Loading, listOf(PlanningEffect.Load))
    }

    @Test
    fun `loaded plans are ready and external edits are saved again`() {
        val plans = mapOf(Chat to plan())
        val bindings = mapOf(Session to Chat)
        spec.assertTransition(PlanningState.Loading, Internal.Loaded(plans, bindings), Ready(plans, bindings))
        spec.assertTransition(
            from = PlanningState.Loading,
            intent = Internal.Loaded(plans, bindings, external = setOf(Chat)),
            to = Ready(plans, bindings),
            effects = listOf(PlanningEffect.Persist(plans, bindings, setOf(Chat), 0)),
        )
    }

    @Test
    fun `commands before loading are ignored`() {
        spec.assertIgnored(PlanningState.Idle, Public.Enter(Chat, At))
        spec.assertIgnored(PlanningState.Loading, Public.Bind(Chat, Session))
        spec.assertIgnored(PlanningState.Failed, Public.Write(Chat, PlanEdit.Replace("x"), 0, PlanAuthor.User, At))
    }

    @Test
    fun `enter creates an empty plan or resumes planning a detached one`() {
        val created = Ready(mapOf(Chat to plan()), revision = 1)
        spec.assertTransition(Ready(), Public.Enter(Chat, At), created, listOf(persist(created)))
        val detached = Ready(mapOf(Chat to plan(mode = PlanMode.Detached, text = "# A")), revision = 4)
        val resumed = Ready(mapOf(Chat to plan(text = "# A")), revision = 5)
        spec.assertTransition(detached, Public.Enter(Chat, At), resumed, listOf(persist(resumed)))
        spec.assertIgnored(created, Public.Enter(Chat, At))
    }

    @Test
    fun `enter beyond the limit evicts the oldest detached plan or is refused`() {
        val full = (0 until PlanLimits.MAX_PLANS).associate { index ->
            val chat = PlanChat("p$index")
            chat to Plan(chat, PlanMode.Detached, updatedAt = Instant.fromEpochSeconds(index.toLong() + 1))
        }
        val after = Ready(full - PlanChat("p0") + (Chat to plan()), revision = 2)
        spec.assertTransition(Ready(full), Public.Enter(Chat, At), after, listOf(persist(after, PlanChat("p0"))))
        val busy = full.mapValues { (_, plan) -> plan.copy(mode = PlanMode.Planning) }
        spec.assertIgnored(Ready(busy), Public.Enter(Chat, At))
    }

    @Test
    fun `leave detaches the plan and keeps it`() {
        val left = Ready(mapOf(Chat to plan(mode = PlanMode.Detached)), revision = 1)
        spec.assertTransition(planning(), Public.Leave(Chat), left, listOf(persist(left)))
        spec.assertIgnored(left, Public.Leave(Chat))
    }

    @Test
    fun `bind keeps one session per chat`() {
        val first = Ready(mapOf(Chat to plan()), mapOf(Session to Chat), revision = 1)
        spec.assertTransition(planning(), Public.Bind(Chat, Session), first, listOf(persist(first)))
        val next = Ready(mapOf(Chat to plan()), mapOf(Next to Chat), revision = 2)
        spec.assertTransition(first, Public.Bind(Chat, Next), next, listOf(persist(next)))
        spec.assertIgnored(first, Public.Bind(Chat, Session))
        spec.assertIgnored(first, Public.Bind(PlanChat("other"), Session))
    }

    @Test
    fun `write from the current revision changes the text and returns the plan to draft`() {
        val proposed = planning(plan(status = PlanStatus.Proposed, text = "# A"))
        val written = Ready(
            mapOf(Chat to plan(revision = 1, text = "# B", updatedAt = Later, updatedBy = PlanAuthor.Agent)),
            revision = 1,
        )
        spec.assertTransition(
            from = proposed,
            intent = Public.Write(Chat, PlanEdit.Replace("# B"), 0, PlanAuthor.Agent, Later),
            to = written,
            effects = listOf(persist(written, Chat)),
        )
    }

    @Test
    fun `write while implementing keeps the approval`() {
        val implementing = planning(plan(mode = PlanMode.Implementing, status = PlanStatus.Approved))
        val written = Ready(
            mapOf(
                Chat to plan(
                    mode = PlanMode.Implementing,
                    status = PlanStatus.Approved,
                    revision = 1,
                    text = "# Done",
                    updatedAt = Later,
                    updatedBy = PlanAuthor.Agent,
                ),
            ),
            revision = 1,
        )
        spec.assertTransition(
            from = implementing,
            intent = Public.Write(Chat, PlanEdit.Replace("# Done"), 0, PlanAuthor.Agent, Later),
            to = written,
            effects = listOf(persist(written, Chat)),
        )
    }

    @Test
    fun `stale unchanged oversized and detached agent writes are ignored`() {
        val state = planning(plan(revision = 2, text = "# A"))
        spec.assertIgnored(state, Public.Write(Chat, PlanEdit.Replace("# B"), 1, PlanAuthor.Agent, Later))
        spec.assertIgnored(state, Public.Write(Chat, PlanEdit.Replace("# A"), 2, PlanAuthor.User, Later))
        val huge = PlanEdit.Replace("x".repeat(PlanLimits.MAX_CHARS + 1))
        spec.assertIgnored(state, Public.Write(Chat, huge, 2, PlanAuthor.User, Later))
        val detached = planning(plan(mode = PlanMode.Detached, revision = 2))
        spec.assertIgnored(detached, Public.Write(Chat, PlanEdit.Replace("# B"), 2, PlanAuthor.Agent, Later))
        spec.assertIgnored(Ready(), Public.Write(Chat, PlanEdit.Replace("# B"), 0, PlanAuthor.User, Later))
    }

    @Test
    fun `user edits a detached plan`() {
        val detached = planning(plan(mode = PlanMode.Detached))
        val written = Ready(
            mapOf(Chat to plan(mode = PlanMode.Detached, revision = 1, text = "# B", updatedAt = Later)),
            revision = 1,
        )
        spec.assertTransition(
            from = detached,
            intent = Public.Write(Chat, PlanEdit.Replace("# B"), 0, PlanAuthor.User, Later),
            to = written,
            effects = listOf(persist(written, Chat)),
        )
    }

    @Test
    fun `propose and approve the current revision`() {
        val proposed = Ready(mapOf(Chat to plan(status = PlanStatus.Proposed)), revision = 1)
        spec.assertTransition(planning(), Public.Propose(Chat, 0), proposed, listOf(persist(proposed)))
        spec.assertIgnored(planning(), Public.Propose(Chat, 1))
        spec.assertIgnored(proposed, Public.Propose(Chat, 0))
        val approved = Ready(mapOf(Chat to plan(status = PlanStatus.Approved)), revision = 2)
        spec.assertTransition(proposed, Public.Approve(Chat, 0), approved, listOf(persist(approved)))
        spec.assertIgnored(approved, Public.Approve(Chat, 0))
        spec.assertIgnored(proposed, Public.Approve(Chat, 1))
        val draftApproved = Ready(mapOf(Chat to plan(status = PlanStatus.Approved)), revision = 1)
        spec.assertTransition(planning(), Public.Approve(Chat, 0), draftApproved, listOf(persist(draftApproved)))
    }

    @Test
    fun `starting the implementation approves the revision and lifts planning`() {
        val implementing = Ready(
            mapOf(Chat to plan(mode = PlanMode.Implementing, status = PlanStatus.Approved)),
            revision = 1,
        )
        spec.assertTransition(
            planning(),
            Public.StartImplementation(Chat, 0),
            implementing,
            listOf(persist(implementing)),
        )
        spec.assertIgnored(planning(), Public.StartImplementation(Chat, 1))
        spec.assertIgnored(implementing, Public.StartImplementation(Chat, 0))
    }

    @Test
    fun `forget deletes the plan and its bindings`() {
        val state = Ready(mapOf(Chat to plan()), mapOf(Session to Chat))
        val forgotten = Ready(revision = 1)
        spec.assertTransition(state, Public.Forget(Chat), forgotten, listOf(persist(forgotten, Chat)))
        spec.assertIgnored(forgotten, Public.Forget(Chat))
    }

    @Test
    fun `failed save is reported and the state is kept`() {
        spec.assertTransition(
            from = planning(),
            intent = Internal.SaveFailed,
            to = planning(),
            outputs = listOf(PlanningOutput.SaveFailed),
        )
    }

    @Test
    fun `bound plan is found by its session`() {
        val state = Ready(mapOf(Chat to plan()), mapOf(Session to Chat))
        kotlin.test.assertEquals(plan(), state.planOf(Session))
        kotlin.test.assertEquals(null, state.planOf(Next))
    }

    private fun planning(plan: Plan = plan()) = Ready(mapOf(Chat to plan))

    private fun plan(
        mode: PlanMode = PlanMode.Planning,
        status: PlanStatus = PlanStatus.Draft,
        revision: Long = 0,
        text: String = "",
        updatedAt: Instant = At,
        updatedBy: PlanAuthor = PlanAuthor.User,
    ) = Plan(Chat, mode, status, revision, text, updatedAt, updatedBy)

    private fun persist(state: Ready, changed: PlanChat? = null) =
        PlanningEffect.Persist(state.plans, state.bindings, setOfNotNull(changed), state.revision)

    private companion object {
        val Chat = PlanChat("chat-1")
        val At: Instant = Instant.fromEpochSeconds(1_000)
        val Later: Instant = Instant.fromEpochSeconds(2_000)
        val Session = SessionRef(EngineId("pi"), SessionSourceId("s"), "one")
        val Next = SessionRef(EngineId("pi"), SessionSourceId("s"), "two")
    }
}
