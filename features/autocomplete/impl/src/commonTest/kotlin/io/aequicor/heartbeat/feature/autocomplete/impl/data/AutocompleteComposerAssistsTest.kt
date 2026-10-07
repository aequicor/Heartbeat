package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningMachineKey
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.autocomplete.api.AutocompleteEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssistOrigin
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class AutocompleteComposerAssistsTest {
    private val pi = EngineId("pi")
    private val target = EngineTarget(pi, EngineBindingId("b1"), ModelId("m1"))
    private val project = WorkspaceRef("project")

    @Test
    fun `the feature toggle gates everything`() = runTest {
        val service = service(toggles = mapOf(), assists = listOf(command("/plan", "Plan")))
        val suggestions = service.suggest(
            ComposerTrigger.Command(0..4, "plan"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(emptyList(), suggestions)
    }

    @Test
    fun `commands offer host items first and keep the engine insertion`() = runTest {
        val service = service(
            assists = listOf(command("/review ", "Review")),
        )
        val suggestions = service.suggest(
            ComposerTrigger.Command(0..2, "re"),
            ComposerScope(target = target, workspace = project),
            hostCommands = listOf(HostCommand("remember", "Remember a lesson", insert = "/remember ")),
        )
        assertEquals(2, suggestions.size)
        val host = assertIs<ComposerSuggestion.Command>(suggestions.first())
        assertEquals(ComposerAssistOrigin.Heartbeat, host.origin)
        assertEquals("/remember ", host.insert)
        val engine = assertIs<ComposerSuggestion.Command>(suggestions[1])
        assertEquals(ComposerAssistOrigin.Engine(pi), engine.origin)
        assertEquals("/review ", engine.insert)
    }

    @Test
    fun `mentions offer learned skills and engine skills, dropping duplicates`() = runTest {
        val service = service(
            assists = listOf(skill("verification", "verification", "engine checks")),
        )
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..6, "ver"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(2, suggestions.size)
        val learned = assertIs<ComposerSuggestion.Skill>(suggestions[0])
        assertEquals(ComposerAssistOrigin.Heartbeat, learned.origin)
        assertEquals("verify ", learned.insert)
        val engine = assertIs<ComposerSuggestion.Skill>(suggestions[1])
        assertEquals(ComposerAssistOrigin.Engine(pi), engine.origin)
    }

    @Test
    fun `a native item duplicating a heartbeat name drops`() = runTest {
        val service = service(
            assists = listOf(skill("verify", "verify", "engine copy")),
        )
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..6, "ver"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(1, suggestions.size)
        assertEquals(ComposerAssistOrigin.Heartbeat, suggestions[0].origin)
    }

    @Test
    fun `self-learning off leaves only engine skills`() = runTest {
        val service = service(
            toggles = mapOf(AutocompleteEnabled to true, AgentLearningEnabled to false),
            assists = listOf(skill("verify", "verify")),
        )
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..3, "ver"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(listOf(ComposerAssistOrigin.Engine(pi)), suggestions.map { it.origin })
    }

    @Test
    fun `engine failures and unavailable routes degrade to host-only suggestions`() = runTest {
        val failing = service(
            toggles = mapOf(AutocompleteEnabled to true),
            assists = listOf(command("/plan", "Plan")),
            failWith = EngineException(EngineFailure.Unknown()),
        )
        val suggestions = failing.suggest(
            ComposerTrigger.Command(0..4, "plan"),
            ComposerScope(target = target, workspace = project),
            hostCommands = listOf(HostCommand("plan", "Plan work", insert = "/plan ")),
        )
        assertEquals(1, suggestions.size)
        assertEquals(ComposerAssistOrigin.Heartbeat, suggestions[0].origin)
    }

    @Test
    fun `engine listings are cached per route until the ttl passes`() = runTest {
        val clock = SteadyClock(Instant.parse("2026-01-01T00:00:00Z"))
        var calls = 0
        val source = EngineAssistsSource(
            facade(listOf(command("/plan", "Plan")), onRead = { calls++ }),
            clock,
        )
        val target1 = EngineTarget(EngineId("pi"), EngineBindingId("b1"), ModelId("m"))
        val target2 = EngineTarget(EngineId("pi"), EngineBindingId("b2"), ModelId("m"))
        source.assists(target1, null)
        source.assists(target1, null)
        assertEquals(1, calls)
        source.assists(target2, null)
        assertEquals(2, calls)
        clock.set(clock.now() + 90.seconds)
        source.assists(target1, null)
        assertEquals(3, calls)
    }

    private fun command(insert: String, label: String, description: String = "") = EngineAssist(
        id = insert.removePrefix("/").removeSuffix(" "),
        label = label,
        description = description,
        insert = insert,
        kind = EngineAssist.Kind.Command,
    )

    private fun skill(id: String, label: String, description: String = "") = EngineAssist(
        id = id,
        label = label,
        description = description,
        insert = "$label ",
        kind = EngineAssist.Kind.Skill,
    )

    private fun service(
        toggles: Map<FeatureToggle.Flag, Boolean> = mapOf(
            AutocompleteEnabled to true,
            AgentLearningEnabled to true,
        ),
        assists: List<EngineAssist> = emptyList(),
        skills: List<LearnedInstruction> = listOf(
            LearnedInstruction(
                id = InstructionId("s1"),
                kind = InstructionKind.Skill,
                project = WorkspaceRef("project"),
                title = "verify",
                content = "run checks",
                description = "project skill",
            ),
        ),
        failWith: Exception? = null,
    ) = AutocompleteComposerAssists(
        FakeToggles(toggles),
        LearnedSkillsSource(
            learningRegistry(MutableStateFlow<AgentLearningState>(AgentLearningState.Ready(instructions = skills))),
        ),
        EngineAssistsSource(facade(assists, onRead = {}, failure = failWith), FakeClock),
    )

    private fun facade(
        assists: List<EngineAssist>,
        onRead: () -> Unit,
        failure: Exception? = null,
    ): EngineFacade {
        val features = object : EngineFeatures {
            override fun <F : io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature> resolve(
                key: EngineFeatureKey<F>,
            ): FeatureAccess<F> {
                onRead()
                @Suppress("UNCHECKED_CAST")
                val feature = object : ListsComposerAssists {
                    override suspend fun assists(
                        target: EngineTarget,
                        workspace: WorkspaceRef?,
                    ): List<EngineAssist> {
                        failure?.let { throw it }
                        return assists
                    }
                } as F
                return FeatureAccess.Available(feature)
            }
        }
        val catalog = object : EngineCatalog {
            override val state = MutableStateFlow<List<EngineInfo>>(emptyList())
            override suspend fun refresh(engine: EngineId) = error("unused")
            override fun features(engine: EngineId) = features
        }
        return object : EngineFacade {
            override val engines = catalog
            override val bindings: EngineBindings get() = error("unused")
            override val models: ModelCatalog get() = error("unused")
            override val sessions: SessionCatalog get() = error("unused")
            override val providerUsage: ProviderUsageCatalog get() = error("unused")
        }
    }

    private object FakeClock : Clock {
        override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
    }

    private class SteadyClock(private var varNow: Instant) : Clock {
        override fun now(): Instant = varNow
        fun set(instant: Instant) {
            varNow = instant
        }
    }
}

internal class FakeToggles(private val values: Map<FeatureToggle.Flag, Boolean>) : FeatureToggles {
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = kotlinx.coroutines.flow.flow { emit(value(toggle)) }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    private fun <T : Any> value(toggle: FeatureToggle<T>): T {
        val flag = toggle as? FeatureToggle.Flag ?: error("Only flags are supported")
        @Suppress("UNCHECKED_CAST")
        return (values[flag] ?: flag.default) as T
    }
}
