package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSafetyReview
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProposal
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSource
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalReview
import io.aequicor.heartbeat.feature.projectconfig.api.ReviewVerdict
import io.aequicor.heartbeat.feature.projectconfig.api.UnitCheck
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigReviews
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.groupUnits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigReaderTest {
    private val project = WorkspaceRef("p")
    private val harnesses = FakeSection()
    private val learning = FakeSection(ConfigSectionId.Learning, isProjectScoped = true)
    private val reader = ConfigReader { listOf(harnesses, learning) }

    @Test
    fun `files group into units of their sections and the rest is reported`() {
        val tree = ConfigTree.of(
            listOf(
                file("harnesses/review.md", "a"),
                file("harnesses/review.txt", "x"),
                file("learning/style.md", "b"),
                file("unknown/a.md", "c"),
                file("README.md", "d"),
            ),
        )

        val grouped = groupUnits(tree, listOf(harnesses, learning))

        assertEquals(listOf("harnesses/review", "learning/style"), grouped.units.map { it.ref.toString() })
        assertEquals(3, grouped.unclaimed.size)
        assertTrue(grouped.unclaimed.all { it.code == ConfigProblemCode.Unsupported })
    }

    @Test
    fun `digests follow paths and texts only`() {
        val one = harnesses.unit("review", "a")
        assertEquals(one.digest, harnesses.unit("review", "a").digest)
        assertNotEquals(one.digest, harnesses.unit("review", "b").digest)
        assertNotEquals(one.digest, harnesses.unit("other", "a").digest)
    }

    @Test
    fun `units equal to their binding are not checked and unavailable sections are left out or reported`() = runTest {
        learning.state.value = null
        val tree = groupUnits(
            ConfigTree.of(
                listOf(file("harnesses/review.md", "a\n"), file("harnesses/new.md", "bad"), file("learning/s.md", "x")),
            ),
            listOf(harnesses, learning),
        )
        val review = tree[ConfigUnitRef(ConfigSectionId.Harnesses, "review")]!!
        val binding = ConfigBinding(project, review.ref, ConfigEntityKey("k"), review.digest, ConfigDigest("e"))
        val snapshot = reader.sections()

        assertEquals(setOf(ConfigSectionId.Learning), snapshot.unavailable)
        val scanned = reader.scan(tree, snapshot, listOf(binding))
        assertEquals(listOf(UnitCheck.Unchanged), scanned.filter { it.ref == review.ref }.map { it.check })
        assertIs<UnitCheck.Invalid>(scanned.single { it.ref.name == "new" }.check)
        assertTrue(scanned.none { it.ref.section == ConfigSectionId.Learning })
        val reported = reader.scan(tree, snapshot, isUnavailableReported = true)
        assertIs<UnitCheck.Invalid>(reported.single { it.ref.section == ConfigSectionId.Learning }.check)
    }

    @Test
    fun `entity digests include bound entities and only project entities of project-scoped sections`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h1", "review", "a"))
        learning.state.value = listOf(
            FakeEntity("l1", "mine", "x", project),
            FakeEntity("l2", "elsewhere", "y", WorkspaceRef("q")),
            FakeEntity("l3", "bound", "z", WorkspaceRef("q")),
            FakeEntity("l4", "global", "w"),
        )
        val bound = ConfigBinding(
            project,
            ConfigUnitRef(ConfigSectionId.Learning, "custom"),
            ConfigEntityKey("l3"),
            ConfigDigest("f"),
            ConfigDigest("e"),
        )

        val digests = reader.entities(reader.sections(), project, listOf(bound))

        assertEquals(listOf("h1", "l1", "l3"), digests.map { it.key.value })
        assertEquals(learning.unit("custom", "z").digest, digests.single { it.key.value == "l3" }.digest)
        assertEquals(harnesses.unit("review", "a").digest, digests.first().digest)
        val bundle = reader.entities(reader.sections(), null).filter { it.section == ConfigSectionId.Learning }
        assertEquals(listOf("l4"), bundle.map { it.key.value })
    }

    @Test
    fun `review texts name the origin and both versions and verdicts map strictly`() {
        val proposal = ConfigProposal(
            ConfigSource.Project(project),
            ConfigUnitRef(ConfigSectionId.Harnesses, "review"),
            ProposalKind.Update,
            ConfigEntityKey("h1"),
            ConfigDigest("d"),
        )
        val text = ConfigReviews.change(proposal, harnesses.unit("review", "new"), harnesses.unit("review", "old"))!!
        assertTrue("project" in text && "new" in text && "old" in text && "Current files" in text)
        val huge = harnesses.unit("review", "x".repeat(ConfigReviews.CHANGE_CHARS))
        assertNull(ConfigReviews.change(proposal, huge, null))
        assertTrue("fake policy" in ConfigReviews.policy("fake policy"))

        val safe = ConfigReviews.result(AgentSafetyReview("safe", "ok"))
        assertEquals(ProposalReview.Done(ReviewVerdict.Safe, "ok"), safe)
        assertEquals(ProposalReview.Done(ReviewVerdict.Risk, "r"), ConfigReviews.result(AgentSafetyReview("risk", "r")))
        assertEquals(
            ProposalReview.Done(ReviewVerdict.Uncertain, ""),
            ConfigReviews.result(AgentSafetyReview("safe", "")),
        )
    }

    private fun file(path: String, text: String) = ConfigFile(ConfigPath(path), text)
}
