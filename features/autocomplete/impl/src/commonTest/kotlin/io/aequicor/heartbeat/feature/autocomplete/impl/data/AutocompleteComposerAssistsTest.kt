package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningState
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionId
import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.agentlearning.api.LearnedInstruction
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthScope
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceDraft
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceInfo
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.EndpointOrigin
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.ProviderId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindings
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigurationState
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeature
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatureKey
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFeatures
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineInfo
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionCatalog
import io.aequicor.heartbeat.feature.aiengine.facade.api.UnavailableEngineConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.AutocompleteEnabled
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssistOrigin
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerScope
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerTrigger
import io.aequicor.heartbeat.feature.autocomplete.api.HostCommand
import io.aequicor.heartbeat.feature.autocomplete.api.applyComposerSuggestion
import io.aequicor.heartbeat.feature.autocomplete.api.composerTrigger
import io.aequicor.heartbeat.feature.autocomplete.impl.domain.fileRank
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AutocompleteComposerAssistsTest {
    private val pi = EngineId("pi")
    private val target = EngineTarget(pi, EngineBindingId("b1"), ModelId("m1"))
    private val project = WorkspaceRef("project")

    @Test
    fun `files complete mentions with support reasons`() = runTest {
        val service = service(
            toggles = mapOf(AutocompleteEnabled to true, AgentLearningEnabled to true, AttachmentsEnabled to true),
        )
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..4, "read"),
            ComposerScope(
                target = target,
                workspace = project,
                inputSupport = PromptInputSupport(
                    resourceMediaTypes = setOf("text/markdown"),
                    maxFileBytes = 1_048_576,
                ),
            ),
        )
        val file = assertIs<ComposerSuggestion.File>(suggestions.single())
        assertEquals("docs/readme.md", file.relativePath)
        assertEquals("readme.md", file.label)
        assertEquals("docs/readme.md · 2 kB", file.description)
        assertTrue(file.isSupported)
        val oversized = service.suggest(
            ComposerTrigger.Mention(0..4, "logo"),
            ComposerScope(
                target = target,
                workspace = project,
                inputSupport = PromptInputSupport(resourceMediaTypes = setOf("image/png"), maxFileBytes = 1_048_576),
            ),
        ).single()
        val logo = assertIs<ComposerSuggestion.File>(oversized)
        assertFalse(logo.isSupported)
        assertTrue("too large" in logo.description)
    }

    @Test
    fun `attachments off or unsupported input hides the file section`() = runTest {
        val off = service(
            toggles = mapOf(AutocompleteEnabled to true, AgentLearningEnabled to true, AttachmentsEnabled to false),
        )
        val none = off.suggest(
            ComposerTrigger.Mention(0..4, "read"),
            ComposerScope(
                target = target,
                workspace = project,
                inputSupport = PromptInputSupport(resourceMediaTypes = setOf("text/markdown")),
            ),
        )
        assertEquals(0, none.count { it is ComposerSuggestion.File })
        val closed = service(
            toggles = mapOf(
                AutocompleteEnabled to true,
                AgentLearningEnabled to true,
                AttachmentsEnabled to true,
            ),
        ).suggest(
            ComposerTrigger.Mention(0..4, "read"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(0, closed.count { it is ComposerSuggestion.File })
    }

    @Test
    fun `the feature toggle gates everything`() = runTest {
        val service = service(toggles = mapOf(AutocompleteEnabled to false), assists = listOf(command("/plan", "Plan")))
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
    fun `mentions offer learned skills and engine skills dropping duplicates`() = runTest {
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
        assertEquals("@verify ", learned.insert)
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
            TestSources(),
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

    @Test
    fun `native skills match either their displayed name or their native id`() = runTest {
        val service = service(
            skills = emptyList(),
            assists = listOf(skill("checks", "verification"), skill("verify-script", "Quality")),
        )
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..3, "ver"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(listOf("verification", "Quality"), suggestions.map { it.label })
    }

    @Test
    fun `native prefix match precedes a heartbeat substring match`() = runTest {
        val service = service(assists = listOf(skill("erify", "erify")))
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..2, "er"),
            ComposerScope(target = target, workspace = project),
        )
        assertEquals(listOf("erify", "verify"), suggestions.map { it.label })
    }

    @Test
    fun `worktree suggestions use source project skills and execution workspace files`() = runTest {
        val checkout = WorkspaceRef("checkout")
        val scopes = mutableListOf<WorkspaceRef?>()
        val service = service(
            toggles = mapOf(AutocompleteEnabled to true, AgentLearningEnabled to true, AttachmentsEnabled to true),
            files = object : ProjectFileIndex {
                override suspend fun search(
                    workspace: WorkspaceRef?,
                    query: String,
                    limit: Int,
                    skillsOnly: Boolean,
                ): List<ProjectFile> {
                    scopes += workspace
                    return emptyList()
                }
            },
        )
        val suggestions = service.suggest(
            ComposerTrigger.Mention(0..3, "ver"),
            ComposerScope(
                target = target,
                workspace = checkout,
                project = project,
                inputSupport = PromptInputSupport(resourceMediaTypes = setOf("text/markdown")),
            ),
        )
        assertEquals(listOf("verify"), suggestions.map { it.label })
        assertEquals<List<WorkspaceRef?>>(listOf(checkout), scopes)
    }

    @Test
    fun `one ranked list is capped at sixteen suggestions`() = runTest {
        val service = service(assists = (1..24).map { command("/task$it ", "task$it") })
        val suggestions = service.suggest(ComposerTrigger.Command(0..0, ""), ComposerScope(target = target))
        assertEquals(16, suggestions.size)
    }

    @Test
    fun `host results arrive before a slow engine`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service(assists = listOf(command("/review ", "review")), onRead = { gate.await() })
        val snapshots = mutableListOf<List<ComposerSuggestion>>()
        val job = launch {
            service.observe(
                ComposerTrigger.Command(0..0, ""),
                ComposerScope(target = target),
                listOf(HostCommand("remember", "remember", insert = "/remember ")),
            ).collect { snapshots += it }
        }
        runCurrent()
        assertEquals(listOf("remember"), snapshots.last().map { it.label })
        gate.complete(Unit)
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("remember", "review"), snapshots.last().map { it.label })
    }

    @Test
    fun `native skills are not hidden by an identically named file`() = runTest {
        val service = service(
            toggles = mapOf(AutocompleteEnabled to true, AttachmentsEnabled to true),
            skills = emptyList(),
            assists = listOf(skill("readme.md", "readme.md")),
        )
        val rows = service.suggest(
            ComposerTrigger.Mention(0..4, "read"),
            ComposerScope(target, project, PromptInputSupport(resourceMediaTypes = setOf("text/markdown"))),
        )
        assertEquals(2, rows.size)
        assertTrue(rows.any { it is ComposerSuggestion.Skill })
        assertTrue(rows.any { it is ComposerSuggestion.File })
    }

    @Test
    fun `configuration changes invalidate cached native metadata and refresh an open list`() = runTest {
        val states = MutableStateFlow(
            EngineConfigurationState(isEnabled = true),
        )
        val config = object : EngineConfiguration by
        UnavailableEngineConfiguration() {
            override val state = states
        }
        var calls = 0
        val source = EngineAssistsSource(
            facade(listOf(command("/plan", "plan")), onRead = { calls++ }),
            FakeClock,
            TestSources(),
            config,
        )
        val snapshots = mutableListOf<List<EngineAssist>>()
        val job = launch { source.observe(target, project).collect { snapshots += it } }
        runCurrent()
        assertEquals(1, calls)
        states.value = states.value.copy(isEnabled = false)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(emptyList(), snapshots[snapshots.lastIndex - 1])
        job.cancelAndJoin()
    }

    @Test
    fun `source revision and forgetting invalidate native rows without changing bindings`() = runTest {
        val auth = TestSources()
        val original = AuthSource.NoAuth(
            AuthSourceInfo(AuthSourceId("s"), "source", AuthRevision.Known("v1")),
            AuthScope(ProviderId("test"), EndpointOrigin("https://example.com")),
        )
        auth.state.value = listOf(original)
        var calls = 0
        val source = EngineAssistsSource(
            facade(listOf(command("/plan", "plan")), onRead = { calls++ }),
            FakeClock,
            auth,
        )
        val job = launch { source.observe(target, project).collect {} }
        runCurrent()
        assertEquals(1, calls)
        auth.state.value = listOf(original.copy(info = original.info.copy(revision = AuthRevision.Known("v2"))))
        runCurrent()
        assertEquals(2, calls)
        auth.forget(original.info.id)
        runCurrent()
        assertEquals(3, calls)
        job.cancelAndJoin()
    }

    @Test
    fun `project skills display metadata and insert a reference without an attachment`() = runTest {
        val file = ProjectFile(
            ".agents/skills/open-pr/SKILL.md",
            "/project/skill",
            200,
            "text/markdown",
            ProjectSkill("open-pr", "Open a draft pull request"),
        )
        val service = service(
            skills = emptyList(),
            files = object : ProjectFileIndex {
                override suspend fun search(
                    workspace: WorkspaceRef?,
                    query: String,
                    limit: Int,
                    skillsOnly: Boolean,
                ): List<ProjectFile> = listOf(file)
            },
        )
        for (text in listOf("@open", "/open", "\$open")) {
            val trigger = composerTrigger(text, text.length)!!
            val row = assertIs<ComposerSuggestion.Skill>(
                service.suggest(trigger, ComposerScope(workspace = project)).single(),
            )
            assertEquals("open-pr", row.label)
            assertEquals("Open a draft pull request", row.description)
            val draft = applyComposerSuggestion(text, trigger, row)
            assertEquals("[/open-pr](.agents/skills/open-pr/SKILL.md) ", draft.text)
            assertEquals(null, draft.attach)
            assertEquals(draft.text.length, draft.caret)
        }
    }

    @Test
    fun `slash lists learned and native skills using their own insertion syntax`() = runTest {
        val service = service(
            assists = listOf(skill("verification", "verification").copy(insert = "/skill:verification ")),
        )
        val rows = service.suggest(ComposerTrigger.Command(0..3, "ver"), ComposerScope(target, project))
        assertEquals(
            listOf("@verify ", "/skill:verification "),
            rows.map { assertIs<ComposerSuggestion.Skill>(it).insert },
        )
    }

    @Test
    fun `native skills take precedence over file metadata with the same name`() = runTest {
        val service = service(
            skills = emptyList(),
            assists = listOf(skill("verify", "verify")),
            files = object : ProjectFileIndex {
                override suspend fun search(
                    workspace: WorkspaceRef?,
                    query: String,
                    limit: Int,
                    skillsOnly: Boolean,
                ): List<ProjectFile> = listOf(
                    ProjectFile(
                        ".agents/skills/verify/SKILL.md",
                        "/skill",
                        1,
                        "text/markdown",
                        ProjectSkill("verify", "Checks"),
                    ),
                )
            },
        )
        val rows = service.suggest(ComposerTrigger.Command(0..3, "ver"), ComposerScope(target, project))
        assertEquals(listOf(ComposerAssistOrigin.Engine(pi)), rows.map { it.origin })
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
        onRead: suspend () -> Unit = {},
        files: ProjectFileIndex = object : ProjectFileIndex {
            override suspend fun search(
                workspace: WorkspaceRef?,
                query: String,
                limit: Int,
                skillsOnly: Boolean,
            ): List<ProjectFile> = listOf(
                ProjectFile("docs/readme.md", "C:\\p\\docs\\readme.md", 2_048, "text/markdown"),
                ProjectFile("assets/logo.png", "C:\\p\\assets\\logo.png", 90_000_000, "image/png"),
            ).filter { fileRank(query, it.relativePath) >= 0 }
        },
    ) = AutocompleteComposerAssists(
        FakeToggles(toggles),
        LearnedSkillsSource(
            learningRegistry(MutableStateFlow<AgentLearningState>(AgentLearningState.Ready(instructions = skills))),
        ),
        EngineAssistsSource(facade(assists, onRead = onRead, failure = failWith), FakeClock, TestSources()),
        files,
    )

    private fun facade(
        assists: List<EngineAssist>,
        onRead: suspend () -> Unit,
        failure: Exception? = null,
    ): EngineFacade {
        val features = object : EngineFeatures {
            override fun <F : EngineFeature> resolve(key: EngineFeatureKey<F>): FeatureAccess<F> {
                @Suppress("UNCHECKED_CAST")
                val feature = object : ListsComposerAssists {
                    override suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> {
                        onRead()
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
            override val bindings: EngineBindings = TestBindings()
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
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = kotlinx.coroutines.flow.flow {
        emit(
            value(toggle),
        )
    }

    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = value(toggle)

    private fun <T : Any> value(toggle: FeatureToggle<T>): T {
        val flag = toggle as? FeatureToggle.Flag ?: error("Only flags are supported")
        @Suppress("UNCHECKED_CAST")
        return (values[flag] ?: flag.default) as T
    }
}

private class TestBindings : EngineBindings {
    override val state = MutableStateFlow<List<EngineBinding>>(emptyList())
    override suspend fun connect(engine: EngineId, source: AuthSourceId, priority: Int): Nothing = error("unused")
    override suspend fun setEnabled(binding: EngineBindingId, enabled: Boolean): Nothing = error("unused")
    override suspend fun disconnect(binding: EngineBindingId): Nothing = error("unused")
    override suspend fun check(target: EngineTarget, workspace: WorkspaceRef?): Nothing = error("unused")
}

private class TestSources : AuthSources {
    override val state = MutableStateFlow<List<AuthSource>>(emptyList())
    override suspend fun get(id: AuthSourceId) = state.value.firstOrNull { it.info.id == id }
    override suspend fun addManagedKey(label: String, scope: AuthScope, key: Secret): Nothing = error("unused")
    override suspend fun replaceManagedKey(id: AuthSourceId, key: Secret): Nothing = error("unused")
    override suspend fun register(draft: AuthSourceDraft): Nothing = error("unused")
    override suspend fun updateRevision(id: AuthSourceId, revision: AuthRevision): Nothing = error("unused")
    override suspend fun forget(id: AuthSourceId) {
        state.value = state.value.filterNot { it.info.id == id }
    }
}
