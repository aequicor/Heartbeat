package io.aequicor.heartbeat.core.bundles.impl

import io.aequicor.heartbeat.core.bundles.BundleCycleException
import io.aequicor.heartbeat.core.bundles.BundleEntry
import io.aequicor.heartbeat.core.bundles.BundleHealth
import io.aequicor.heartbeat.core.bundles.BundleId
import io.aequicor.heartbeat.core.bundles.BundleUnavailableException
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.di.ScopeSavedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

interface Renderer

interface Opener

interface Tool

interface WebExports {
    val renderer: Renderer
    val tools: Set<Tool>
}

interface ToolExports {
    val tools: Set<Tool>
}

interface WebImports {
    val opener: Opener
}

interface StudioExports {
    val opener: Opener
    val tools: Set<Tool>
}

interface StudioImports {
    val renderer: Renderer
    val tools: Set<Tool>
}

interface CycleAImports {
    val opener: Opener
}

interface CycleAExports {
    val renderer: Renderer
}

interface CycleBImports {
    val renderer: Renderer
}

interface CycleBExports {
    val opener: Opener
}

class BundleHostTest {

    private class Tools(override val tools: Set<Tool>) : ToolExports

    private class Web(override val renderer: Renderer = object : Renderer {}, toolSet: Set<Tool> = emptySet()) :
        WebExports {
        override val tools: Set<Tool> = toolSet
    }

    private class Studio(override val opener: Opener = object : Opener {}, toolSet: Set<Tool> = emptySet()) :
        StudioExports {
        override val tools: Set<Tool> = toolSet
    }

    private fun entry(
        name: String,
        imports: KClass<*>? = null,
        exports: List<KClass<*>> = emptyList(),
        create: (Any) -> Any,
    ): BundleEntry = object : BundleEntry {
        override val id = BundleId(name)
        override val importsType = imports
        override val exportTypes = exports
        override fun createGraph(foundation: Any, imports: Any): Any = create(imports)
    }

    private fun started(vararg entries: BundleEntry): BundleHost = BundleHost(entries.toList()) { Unit }.also {
        it.start()
        it.seal()
    }

    @Test
    fun `key is the generic return type without variance wildcards`() {
        val set = WebExports::class.java.getMethod("getTools").genericReturnType
        assertEquals("java.util.Set<${Tool::class.java.name}>", bindingKeyOf(set).signature)
    }

    @Test
    fun `service of one bundle is resolved by another through its imports`() {
        val renderer = object : Renderer {}
        var seen: StudioImports? = null
        val host = started(
            entry("web", exports = listOf(WebExports::class)) { Web(renderer) },
            entry("studio", imports = StudioImports::class) { imports ->
                seen = imports as StudioImports
                Any()
            },
        )
        assertSame(renderer, seen?.renderer)
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("studio")))
    }

    @Test
    fun `imports used while the graph is created fail that bundle only`() {
        val host = started(
            entry("web", exports = listOf(WebExports::class)) { Web() },
            entry("studio", imports = StudioImports::class) { imports -> (imports as StudioImports).renderer },
        )
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("studio")))
    }

    @Test
    fun `bundle failing at creation is isolated and others start`() {
        val host = started(
            entry("bad") { error("boom") },
            entry("good", exports = listOf(WebExports::class)) { Web() },
        )
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("bad")))
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("good")))
    }

    @Test
    fun `import without exporter degrades the bundle and fails on use`() {
        var seen: StudioImports? = null
        val host = started(
            entry("studio", imports = StudioImports::class) { imports ->
                seen = imports as StudioImports
                Any()
            },
        )
        val health = host.health.getValue(BundleId("studio"))
        assertIs<BundleHealth.Degraded>(health)
        assertEquals(1, health.missing.size)
        assertFailsWith<BundleUnavailableException> { seen?.renderer }
    }

    @Test
    fun `failed exporter makes its importers degraded`() {
        val host = started(
            entry("web", exports = listOf(WebExports::class)) { error("no") },
            entry("studio", imports = StudioImports::class) { Any() },
        )
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("web")))
        assertIs<BundleHealth.Degraded>(host.health.getValue(BundleId("studio")))
    }

    @Test
    fun `second exporter of a single binding fails`() {
        val host = started(
            entry("a", exports = listOf(WebExports::class)) { Web() },
            entry("b", exports = listOf(WebExports::class)) { Web() },
        )
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("a")))
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("b")))
    }

    @Test
    fun `imported set is the union of the sets exported by other bundles`() {
        val toolA = object : Tool {}
        val toolB = object : Tool {}
        var seen: StudioImports? = null
        started(
            entry("a", exports = listOf(ToolExports::class)) { Tools(setOf(toolA)) },
            entry("b", exports = listOf(ToolExports::class)) { Tools(setOf(toolB)) },
            entry("studio", imports = StudioImports::class) { imports ->
                seen = imports as StudioImports
                Any()
            },
        )
        assertEquals(setOf(toolA, toolB), seen?.tools)
    }

    @Test
    fun `bundle that imports and exports the same set fails`() {
        val host = started(
            entry("studio", imports = StudioImports::class, exports = listOf(StudioExports::class)) { Studio() },
        )
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("studio")))
    }

    @Test
    fun `real cross-bundle cycle is reported`() {
        val renderer = object : Renderer {}
        var aImports: CycleAImports? = null
        var bImports: CycleBImports? = null
        val opener = object : Opener {}
        val host = started(
            entry("a", imports = CycleAImports::class, exports = listOf(CycleAExports::class)) { imports ->
                aImports = imports as CycleAImports
                object : CycleAExports {
                    override val renderer: Renderer get() = aImports!!.opener.let { renderer }
                }
            },
            entry("b", imports = CycleBImports::class, exports = listOf(CycleBExports::class)) { imports ->
                bImports = imports as CycleBImports
                object : CycleBExports {
                    override val opener: Opener get() = bImports!!.renderer.let { opener }
                }
            },
        )
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("a")))
        assertFailsWith<BundleCycleException> { aImports?.opener }
    }

    private class FakeScope : ScopeHandle {
        private val actions = ArrayList<() -> Unit>()
        override val name = "profile"
        override val coroutineScope: CoroutineScope get() = error("unused")
        override val savedState: ScopeSavedState get() = error("unused")
        override var isClosed = false

        override fun onClose(action: () -> Unit): DisposableHandle {
            actions += action
            return DisposableHandle { actions -= action }
        }

        fun close() {
            isClosed = true
            actions.reversed().forEach { it() }
        }
    }

    private interface ProfileExports {
        val opener: Opener
    }

    private interface ProfileImports {
        val renderer: Renderer
    }

    private fun profileEntry(
        name: String,
        opener: Opener,
        onProfile: (foundation: Any, imports: ProfileImports) -> Unit = { _, _ -> },
    ) = object : BundleEntry {
        override val id = BundleId(name)
        override val importsType: KClass<*>? = null
        override val exportTypes = listOf(WebExports::class)
        override fun createGraph(foundation: Any, imports: Any): Any = Web()
        override val hasProfileGraph = true
        override val profileImportsType: KClass<*> = ProfileImports::class
        override val profileExportTypes = listOf(ProfileExports::class)
        override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any {
            onProfile(foundation, imports as ProfileImports)
            return object : ProfileExports {
                override val opener = opener
            }
        }
    }

    @Test
    fun `profile graph sees app exports and is dropped with the profile scope`() {
        val foundation = Any()
        var seenFoundation: Any? = null
        var imports: ProfileImports? = null
        val host = BundleHost(
            listOf(
                profileEntry("web", object : Opener {}) { f, i ->
                    seenFoundation = f
                    imports = i
                },
            ),
        ) { Unit }
        val scope = FakeScope()
        host.ensureStarted()
        val health = host.openProfile(foundation, scope)
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("web")))
        assertSame(foundation, seenFoundation)
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
        assertIs<Renderer>(imports?.renderer)
        scope.close()
        assertFailsWith<IllegalStateException> { imports?.renderer }
    }

    @Test
    fun `profile exports are private to their profile and win over app exports`() {
        val first = FakeScope()
        val second = FakeScope()
        val openerA = object : Opener {}
        val host = BundleHost(listOf(profileEntry("web", openerA))) { Unit }
        host.ensureStarted()
        host.openProfile(Any(), first)
        host.openProfile(Any(), second)
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
        first.close()
        host.openProfile(Any(), FakeScope())
    }

    private interface FoundationProfileImports {
        val opener: Opener
    }

    @Test
    fun `foundation reads the profile exports of its own profile lazily`() {
        val openerA = object : Opener {}
        val openerB = object : Opener {}
        val first = FakeScope()
        val second = FakeScope()
        val host = BundleHost(listOf(profileEntry("web", openerA))) { Unit }
        val early = host.foundationProfileImports(FoundationProfileImports::class, first)
        assertFailsWith<BundleUnavailableException> { early.opener }
        host.openProfile(Any(), first)
        assertSame(openerA, early.opener)
        val other = BundleHost(listOf(profileEntry("web", openerB))) { Unit }
        other.openProfile(Any(), second)
        assertSame(openerB, other.foundationProfileImports(FoundationProfileImports::class, second).opener)
        assertFailsWith<BundleUnavailableException> {
            other.foundationProfileImports(FoundationProfileImports::class, first).opener
        }
        first.close()
        assertFailsWith<BundleUnavailableException> { early.opener }
    }

    @Test
    fun `ensureStarted is idempotent and start and seal run once`() {
        var created = 0
        val host = BundleHost(
            listOf(
                entry("a", exports = listOf(WebExports::class)) {
                    created++
                    Web()
                },
            ),
        ) { Unit }
        host.ensureStarted()
        host.ensureStarted()
        assertEquals(1, created)
    }

    private interface ScopeExports {
        val scope: ScopeHandle
    }

    private fun scopeEntry() = entry("dup", exports = listOf(ScopeExports::class)) {
        object : ScopeExports {
            override val scope: ScopeHandle get() = error("never read")
        }
    }

    @Test
    fun `bundle exporting a key the foundation owns fails`() {
        val host = started(scopeEntry(), entry("web", exports = listOf(WebExports::class)) { Web() })
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("dup")))
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
    }

    @Test
    fun `foundation owned keys cover app and profile bindings and skip sets`() {
        val keys = foundationOwnedKeys().map { it.signature }
        assertEquals(true, ScopeHandle::class.java.name in keys)
        assertEquals(true, keys.none { it.startsWith("java.util.Set") })
    }

    @Test
    fun `smaller owned set is a deliberate exception`() {
        val host = BundleHost(listOf(scopeEntry()), { Unit }, emptySet()).also {
            it.start()
            it.seal()
        }
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("dup")))
    }

    @Test
    fun `failing profile graph is isolated and reported`() {
        val failing = object : BundleEntry {
            override val id = BundleId("bad")
            override val importsType: KClass<*>? = null
            override val exportTypes = emptyList<KClass<*>>()
            override fun createGraph(foundation: Any, imports: Any): Any = Any()
            override val hasProfileGraph = true
            override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any = error("boom")
        }
        val host = BundleHost(listOf(failing, profileEntry("web", object : Opener {}))) { Unit }
        host.ensureStarted()
        val health = host.openProfile(Any(), FakeScope())
        assertIs<BundleHealth.Failed>(health.getValue(BundleId("bad")))
        assertEquals(BundleHealth.Healthy, health.getValue(BundleId("web")))
    }

    @Test
    fun `exporter failing on first read is failed and the consumer gets unavailable with the cause`() {
        val boom = IllegalStateException("boom")
        var seen: StudioImports? = null
        val host = started(
            entry("web", exports = listOf(WebExports::class)) {
                object : WebExports {
                    override val renderer: Renderer get() = throw boom
                    override val tools: Set<Tool> = emptySet()
                }
            },
            entry("studio", imports = StudioImports::class) { imports ->
                seen = imports as StudioImports
                Any()
            },
        )
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
        val failure = assertFailsWith<BundleUnavailableException> { seen?.renderer }
        assertSame(boom, failure.cause)
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("web")))
        val again = assertFailsWith<BundleUnavailableException> { seen?.renderer }
        assertEquals(null, again.cause)
    }

    @Test
    fun `cancellation of an exporter is rethrown and does not fail the bundle`() {
        var seen: StudioImports? = null
        val host = started(
            entry("web", exports = listOf(WebExports::class)) {
                object : WebExports {
                    override val renderer: Renderer get() = throw CancellationException("cancelled")
                    override val tools: Set<Tool> = emptySet()
                }
            },
            entry("studio", imports = StudioImports::class) { imports ->
                seen = imports as StudioImports
                Any()
            },
        )
        assertFailsWith<CancellationException> { seen?.renderer }
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
    }

    @Test
    fun `set exporter failing on read is skipped and failed while others contribute`() {
        val toolA = object : Tool {}
        var seen: StudioImports? = null
        val host = started(
            entry("a", exports = listOf(ToolExports::class)) { Tools(setOf(toolA)) },
            entry("b", exports = listOf(ToolExports::class)) {
                object : ToolExports {
                    override val tools: Set<Tool> get() = error("broken toggles")
                }
            },
            entry("studio", imports = StudioImports::class) { imports ->
                seen = imports as StudioImports
                Any()
            },
        )
        assertEquals(setOf(toolA), seen?.tools)
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("b")))
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("a")))
    }

    @Test
    fun `second start is rejected instead of failing every bundle`() {
        val host = BundleHost(listOf(entry("a", exports = listOf(WebExports::class)) { Web() })) { Unit }
        host.start()
        assertFailsWith<IllegalStateException> { host.start() }
        host.ensureStarted()
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("a")))
    }

    @Test
    fun `foundation is not a bundle id`() {
        assertFailsWith<IllegalArgumentException> { BundleHost(listOf(entry("foundation") { Any() })) { Unit } }
    }

    /** Profile bundle whose [ProfileExports.opener] is computed on every read from the profile's foundation. */
    private fun readingProfileEntry(name: String, read: (foundation: Any, imports: ProfileImports) -> Opener) =
        object : BundleEntry {
            override val id = BundleId(name)
            override val importsType: KClass<*>? = null
            override val exportTypes = listOf(WebExports::class)
            override fun createGraph(foundation: Any, imports: Any): Any = Web()
            override val hasProfileGraph = true
            override val profileImportsType: KClass<*> = ProfileImports::class
            override val profileExportTypes = listOf(ProfileExports::class)
            override fun createProfileGraph(appGraph: Any, foundation: Any, imports: Any): Any =
                object : ProfileExports {
                    override val opener: Opener get() = read(foundation, imports as ProfileImports)
                }
        }

    @Test
    fun `profile exporter failure removes it from that profile only`() {
        val broken = Any()
        val opener = object : Opener {}
        val host = BundleHost(
            listOf(
                readingProfileEntry("web") { foundation, _ -> if (foundation === broken) error("boom") else opener },
            ),
        ) { Unit }
        val first = FakeScope()
        val second = FakeScope()
        host.openProfile(broken, first)
        host.openProfile(Any(), second)
        val firstImports = host.foundationProfileImports(FoundationProfileImports::class, first)
        val failure = assertFailsWith<BundleUnavailableException> { firstImports.opener }
        assertEquals("boom", failure.cause?.message)
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
        assertSame(opener, host.foundationProfileImports(FoundationProfileImports::class, second).opener)
        assertEquals(null, assertFailsWith<BundleUnavailableException> { firstImports.opener }.cause)
    }

    @Test
    fun `profile exporter failing while its profile closes keeps the app healthy`() {
        val scope = FakeScope()
        val host = BundleHost(
            listOf(
                readingProfileEntry("web") { _, _ ->
                    scope.close()
                    error("boom")
                },
            ),
        ) { Unit }
        host.openProfile(Any(), scope)
        assertFailsWith<BundleUnavailableException> {
            host.foundationProfileImports(FoundationProfileImports::class, scope).opener
        }
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
    }

    @Test
    fun `nested import failing withdraws only its exporter`() {
        val opener = object : Opener {}
        var bImports: CycleBImports? = null
        var seen: WebImports? = null
        val host = started(
            entry("b", imports = CycleBImports::class, exports = listOf(CycleBExports::class)) { imports ->
                bImports = imports as CycleBImports
                object : CycleBExports {
                    override val opener: Opener get() = bImports!!.renderer.let { opener }
                }
            },
            entry("c", exports = listOf(WebExports::class)) {
                object : WebExports {
                    override val renderer: Renderer get() = error("c is broken")
                    override val tools: Set<Tool> = emptySet()
                }
            },
            entry("studio", imports = WebImports::class) { imports ->
                seen = imports as WebImports
                Any()
            },
        )
        assertFailsWith<BundleUnavailableException> { seen?.opener }
        assertIs<BundleHealth.Failed>(host.health.getValue(BundleId("c")))
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("b")))
        assertFailsWith<BundleUnavailableException> { seen?.opener }
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("b")))
    }

    @Test
    fun `host condition inside a nested import does not withdraw the asking exporter`() {
        val scope = FakeScope()
        val second = FakeScope()
        val opener = object : Opener {}
        val host = BundleHost(
            listOf(
                readingProfileEntry("web") { _, imports ->
                    if (!scope.isClosed) scope.close()
                    imports.renderer
                    opener
                },
            ),
        ) { Unit }
        host.openProfile(Any(), scope)
        host.openProfile(Any(), second)
        val failure = assertFailsWith<BundleUnavailableException> {
            host.foundationProfileImports(FoundationProfileImports::class, scope).opener
        }
        assertIs<BundleUnavailableException>(failure.cause)
        assertEquals(BundleHealth.Healthy, host.health.getValue(BundleId("web")))
        assertSame(opener, host.foundationProfileImports(FoundationProfileImports::class, second).opener)
    }
}
