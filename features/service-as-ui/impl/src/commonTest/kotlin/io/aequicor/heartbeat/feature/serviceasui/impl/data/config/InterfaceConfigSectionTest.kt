package io.aequicor.heartbeat.feature.serviceasui.impl.data.config

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyContext
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigApplyResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigCheck
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnit
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEffect
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiMachineSpec
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.api.UiSlot
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceContent
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class InterfaceConfigSectionTest {
    private val at = Instant.fromEpochMilliseconds(1_000)
    private val review = UiOwner("harness", "h_review")
    private val board = UiSurface(
        UiSurfaceContent(
            review,
            UiSurfaceName("board"),
            UiPlacement.Panel,
            "Board",
            "Open reviews",
            setOf(UiSlot.Composer),
            TEXT,
        ),
        isEnabled = false,
        createdAt = at,
        updatedAt = at,
    )
    private val foreign = board.copy(content = board.content.copy(owner = UiOwner("other", "x")))
    private val owners = mapOf(
        "harness" to UiOwners(
            isComplete = true,
            owners = listOf(
                UiOwnerInfo("h_review", "review", "Review", true),
                UiOwnerInfo("h_audit", "audit", "A", true),
            ),
        ),
    )
    private val machine = SpecUiMachine(
        ServiceUiState.Ready(owners = owners, library = UiLibrary(listOf(board, foreign))),
    )
    private val toggles = Toggles()
    private val section = InterfaceConfigSection(machine, toggles)

    @Test
    fun `surfaces of known harnesses encode to files that check back to the same canonical unit`() = runTest {
        val entity = section.entities.first().orEmpty().single()
        assertEquals("review/board", entity.name)
        assertEquals(ConfigEntityKey("h_review"), entity.parent)
        assertEquals("review", section.parentOf(entity.name))

        val unit = section.encode(entity.key, entity.name)!!
        assertEquals(
            listOf("interfaces/review/board.json", "interfaces/review/board.spec.json"),
            unit.files.map { it.path.value },
        )
        assertTrue("\n" in unit.files.last().text.trim())
        val check = assertIs<ConfigCheck.Valid>(section.check(unit))
        assertEquals(unit, check.canonical)
        assertEquals("review/board", check.identity)
        assertFalse(check.summary.hasCode)
        assertEquals("review/board", section.unitOf(ConfigPath("interfaces/review/board.spec.json")))
        assertNull(section.unitOf(ConfigPath("interfaces/review/board.html")))
    }

    @Test
    fun `a unit is written for its harness and a bound one replaced keeping the switch`() = runTest {
        val unit = section.encode(ConfigEntityKey(board.id.value), "review/board")!!
        val audit = ConfigUnit.of(
            ConfigUnitRef(ConfigSectionId.Interfaces, "audit/board"),
            unit.files.map { ConfigFile(ConfigPath(it.path.value.replace("/review/", "/audit/")), it.text) },
        )

        val created = section.apply(audit, null, ConfigApplyContext(null, parent = ConfigEntityKey("h_audit")))
        val id = UiSurfaceId.of(UiOwner("harness", "h_audit"), UiSurfaceName("board"))
        assertEquals(ConfigApplyResult.Applied(ConfigEntityKey(id.value)), created)
        assertTrue(library()[id]!!.isEnabled)

        val retitled = ConfigUnit.of(unit.ref, unit.files.map { it.retitled() })
        assertEquals(
            ConfigApplyResult.Applied(ConfigEntityKey(board.id.value)),
            section.apply(retitled, ConfigEntityKey(board.id.value), ConfigApplyContext(null)),
        )
        assertEquals("Queue", library()[board.id]!!.content.title)
        assertFalse(library()[board.id]!!.isEnabled)
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.Invalid),
            section.apply(audit, ConfigEntityKey(board.id.value), ConfigApplyContext(null)),
        )
    }

    @Test
    fun `a unit of an unknown harness waits for its owner and then gives up`() = runTest {
        val unit = unit("ghost/board", META, TEXT)

        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.MissingParent),
            section.apply(unit, null, ConfigApplyContext(null)),
        )
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.MissingParent),
            section.apply(unit, null, ConfigApplyContext(null, parent = ConfigEntityKey("h_ghost"))),
        )
    }

    @Test
    fun `invalid units name their problems`() = runTest {
        assertEquals(ConfigProblemCode.Name, problem(unit("Review/board", META, TEXT)))
        val metadataOnly = listOf(ConfigFile(ConfigPath("interfaces/review/board.json"), META))
        assertEquals(ConfigProblemCode.Missing, problem(ConfigUnit.of(ref("review/board"), metadataOnly)))
        assertEquals(
            ConfigProblemCode.Syntax,
            problem(unit("review/board", """{"placement":"composer","title":"B","launchers":["start"]}""", TEXT)),
        )
        assertEquals(ConfigProblemCode.Syntax, problem(unit("review/board", META, "not json")))
        assertEquals(ConfigProblemCode.Syntax, problem(unit("review/board", META, "[".repeat(100) + "]".repeat(100))))
        assertEquals(
            ConfigProblemCode.Syntax,
            problem(unit("review/board", META, """{"version":1,"body":{"type":"nope"}}""")),
        )
    }

    @Test
    fun `removal deletes the surface and interfaces off make the section unavailable`() = runTest {
        assertEquals(
            ConfigApplyResult.Applied(ConfigEntityKey(board.id.value)),
            section.remove(ConfigEntityKey(board.id.value)),
        )
        assertNull(library()[board.id])

        toggles.isEnabled = false
        assertNull(InterfaceConfigSection(machine, toggles).entities.first())
        assertEquals(
            ConfigApplyResult.Rejected(ConfigRejection.Unavailable),
            section.apply(unit("review/board", META, TEXT), null, ConfigApplyContext(null)),
        )
    }

    private suspend fun problem(unit: ConfigUnit) = assertIs<ConfigCheck.Invalid>(
        section.check(unit),
    ).problems.first().code

    private fun library() = (machine.state.value as ServiceUiState.Ready).library

    private fun ConfigFile.retitled() =
        if (path.value.endsWith("board.json")) ConfigFile(path, text.replace("Board", "Queue")) else this

    private fun ref(name: String) = ConfigUnitRef(ConfigSectionId.Interfaces, name)

    private fun unit(name: String, metadata: String, spec: String): ConfigUnit {
        val (harness, surface) = name.split('/')
        return ConfigUnit.of(
            ref(name),
            listOf(
                ConfigFile(ConfigPath("interfaces/$harness/$surface.json"), metadata),
                ConfigFile(ConfigPath("interfaces/$harness/$surface.spec.json"), spec),
            ),
        )
    }

    private companion object {
        const val TEXT = """{"version":1,"body":{"type":"text","text":"Hi"}}"""
        const val META = """{"placement":"panel","title":"Board"}"""
    }
}

/** Runs the real interface spec; library saves are confirmed at once. */
private class SpecUiMachine(initial: ServiceUiState) : Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput> {
    override val name: String = ServiceUiMachineSpec.name
    override val state = MutableStateFlow(initial)
    override val outputs = MutableSharedFlow<ServiceUiOutput>(extraBufferCapacity = 16)

    override suspend fun send(intent: ServiceUiIntent): SendResult {
        val resolution = ServiceUiMachineSpec.resolve(state.value, intent) ?: return SendResult.Ignored
        state.value = resolution.to
        resolution.outputs.forEach { outputs.emit(it) }
        resolution.effects.filterIsInstance<ServiceUiEffect.Persist>()
            .forEach { send(ServiceUiIntent.Internal.Persisted(it.writeId)) }
        return SendResult.Accepted
    }
}

private class Toggles(var isEnabled: Boolean = true) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // The fake answers only the interfaces flag.
    override fun <T : Any> observe(toggle: FeatureToggle<T>) = flowOf(isEnabled as T)

    @Suppress("UNCHECKED_CAST") // The fake answers only the interfaces flag.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled as T
}
