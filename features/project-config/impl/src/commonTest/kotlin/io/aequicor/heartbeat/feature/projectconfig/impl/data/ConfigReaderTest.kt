package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigDigest
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.UnitCheck
import io.aequicor.heartbeat.feature.projectconfig.api.spi.ConfigSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.groupUnits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
    fun `a section that cannot encode a listed entity is unknown rather than empty`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h1", "review", "a"))
        val changing = object : ConfigSection by harnesses {
            override suspend fun encode(key: ConfigEntityKey, name: String): ConfigUnit? = null
        }
        val reader = ConfigReader { listOf(changing, learning) }
        val read = reader.sections()

        val entities = reader.entities(read, project)

        assertEquals(setOf(ConfigSectionId.Harnesses), entities.unreadable)
        assertTrue(entities.digests.isEmpty())
        // Its bound units are neither proposed nor removed until the owner settles.
        assertEquals(setOf(ConfigSectionId.Harnesses), read.without(entities.unreadable).unavailable)
    }

    @Test
    fun `a text cannot pass for a file boundary in the digest`() {
        val ref = ConfigUnitRef(ConfigSectionId.Harnesses, "x")
        val manifest = ConfigPath.of("harnesses", "x", "harness.json")
        val tool = ConfigPath.of("harnesses", "x", "tool.kts")
        val split = ConfigUnit.of(ref, listOf(ConfigFile(manifest, "A"), ConfigFile(tool, "CODE")))
        val joined = ConfigUnit.of(ref, listOf(ConfigFile(manifest, "A\u0000${tool.value}\u0000CODE")))

        assertNotEquals(split.digest, joined.digest)
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

        val digests = reader.entities(reader.sections(), project, listOf(bound)).digests

        assertEquals(listOf("h1", "l1", "l3"), digests.map { it.key.value })
        assertEquals(learning.unit("custom", "z").digest, digests.single { it.key.value == "l3" }.digest)
        assertEquals(harnesses.unit("review", "a").digest, digests.first().digest)
        val bundle = reader.entities(reader.sections(), null).digests.filter { it.section == ConfigSectionId.Learning }
        assertEquals(listOf("l4"), bundle.map { it.key.value })
    }

    private fun file(path: String, text: String) = ConfigFile(ConfigPath(path), text)
}
