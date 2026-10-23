package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BundleBoundaryTest {

    private val rule = BundleBoundary(Config.empty)

    @Test
    fun `reports the registry and bundles in features`() {
        val code = """
            package io.aequicor.heartbeat.feature.chat.impl

            import io.aequicor.heartbeat.core.bundles.BundleRegistry
            import io.aequicor.heartbeat.core.bundles.impl.BundleHost
            import io.aequicor.heartbeat.bundles.web.WebAppExports
            import io.aequicor.heartbeat.core.navigation.RouteEntry
        """.trimIndent()

        assertEquals(3, rule.lint(code).size)
    }

    @Test
    fun `allows the registry in the platform and in core`() {
        val platform = """
            package io.aequicor.heartbeat.platform.dibundle

            import io.aequicor.heartbeat.core.bundles.impl.BundleHost
        """.trimIndent()
        val core = """
            package io.aequicor.heartbeat.core.bundles.impl

            import io.aequicor.heartbeat.core.bundles.BundleEntry
        """.trimIndent()

        assertEquals(0, rule.lint(platform).size)
        assertEquals(0, rule.lint(core).size)
    }

    @Test
    fun `reports bundle classes and graph casts in the platform`() {
        val code = """
            package io.aequicor.heartbeat.platform.desktop

            import io.aequicor.heartbeat.bundles.web.WebAppExports

            fun open(graph: Any) {
                val tools = graph as ToolsAccessors
                val app = graph as JvmHeartbeatGraph
                val factory = graph as? AiStudioGraph.Factory
                val canvas = graphics as Graphics2D
            }
        """.trimIndent()

        // import + two graph casts
        assertEquals(3, rule.lint(code).size)
    }

    @Test
    fun `leaves casts outside the platform alone`() {
        val code = """
            package io.aequicor.heartbeat.feature.chat.impl

            fun open(graph: Any) = graph as ChatGraph
        """.trimIndent()

        assertEquals(0, rule.lint(code).size)
    }
}
