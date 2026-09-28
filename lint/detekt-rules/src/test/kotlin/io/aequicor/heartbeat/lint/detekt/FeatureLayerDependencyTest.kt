package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureLayerDependencyTest {
    private val rule = FeatureLayerDependency(Config.empty)

    @Test
    fun `search service settings UI may display its own contract values`() {
        assertEquals(
            0,
            rule.lint(
                "package io.aequicor.heartbeat.feature.searchengine.impl.ui\n" +
                    "import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation",
            ).size,
        )
        assertEquals(
            1,
            rule.lint(
                "package io.aequicor.heartbeat.feature.chat.impl.ui\n" +
                    "import io.aequicor.heartbeat.feature.chat.api.ChatIntent",
            ).size,
        )
    }

    @Test
    fun `nested engine modules retain IO and implementation boundaries`() {
        val prefix = "io.aequicor.heartbeat.feature.aiengine"
        assertEquals(1, rule.lint("package $prefix.facade.api\nimport io.ktor.client.HttpClient").size)
        assertEquals(1, rule.lint("package $prefix.codex.impl.data\nimport $prefix.facade.impl.domain.Registry").size)
        assertEquals(0, rule.lint("package $prefix.facade.api\nimport $prefix.authenticator.api.AuthSource").size)
        assertEquals(0, rule.lint("package $prefix.codex.impl.data\nimport $prefix.facade.api.spi.EngineFactory").size)
        assertEquals(
            1,
            rule.lint(
                "package io.aequicor.heartbeat.feature.chat.impl.domain\nimport $prefix.facade.api.spi.EngineFactory",
            ).size,
        )
    }

    @Test
    fun `engine SPI is private even outside feature packages`() {
        val spi = "io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineFactory"
        listOf("core.network", "ds.components", "platform.desktop", "platform.dibundleother").forEach { pkg ->
            assertEquals(1, rule.lint("package io.aequicor.heartbeat.$pkg\nimport $spi").size)
        }
        assertEquals(0, rule.lint("package io.aequicor.heartbeat.platform.dibundle\nimport $spi").size)
        val prefix = "io.aequicor.heartbeat.feature.aiengine"
        assertEquals(1, rule.lint("package $prefix.authenticator.api\nimport $prefix.facade.api.EngineId").size)
    }

    @Test
    fun `fully qualified references survive whitespace and escaped package names`() {
        assertEquals(
            2,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.domain
            val client: io.`ktor`.client.HttpClient? = null
            fun create() = io.ktor.client
                .HttpClient()
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `toggle service imports cannot bypass domain ports using a wildcard`() {
        assertEquals(
            1,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.presentation
            import io.aequicor.heartbeat.core.featuretoggles.*
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `checks every direction between implementation layers`() {
        val allowed = mapOf(
            "domain" to setOf("domain"),
            "data" to setOf("data", "domain"),
            "presentation" to setOf("presentation", "domain"),
            "ui" to setOf("ui", "presentation"),
            "di" to setOf("domain", "data", "presentation", "ui", "di"),
        )
        allowed.forEach { (source, targets) ->
            allowed.keys.forEach { target ->
                val code = """
                    package io.aequicor.heartbeat.feature.chat.impl.$source
                    import io.aequicor.heartbeat.feature.chat.impl.$target.Dependency
                """.trimIndent()
                assertEquals(if (target in targets) 0 else 1, rule.lint(code).size, "$source -> $target")
            }
        }
    }

    @Test
    fun `domain and public contract reject UI DI IO and store frameworks`() {
        listOf("api", "impl.domain").forEach { source ->
            listOf(
                "androidx.compose.runtime.Immutable",
                "io.ktor.client.HttpClient",
                "androidx.room.Entity",
                "io.aequicor.heartbeat.core.datastore.DataStores",
                "pro.respawn.flowmvi.api.Store",
                "dev.zacsweers.metro.Inject",
                "java.io.File",
                "io.aequicor.heartbeat.core.statemachine.flowmvi.reflect",
            ).forEach { dependency ->
                assertEquals(
                    1,
                    rule.lint("package io.aequicor.heartbeat.feature.chat.$source\nimport $dependency").size,
                )
            }
        }
    }

    @Test
    fun `public contract never depends on private implementation`() {
        assertEquals(
            1,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.api
            import io.aequicor.heartbeat.feature.chat.impl.domain.ChatRepository
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `other feature implementations and core implementations remain private even in di`() {
        assertEquals(
            2,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.di
            import io.aequicor.heartbeat.feature.profile.impl.domain.ProfileRepository
            import io.aequicor.heartbeat.core.network.impl.HttpClientFactory
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `aliases wildcard imports fully qualified types and calls cannot bypass boundaries`() {
        val findings = rule.lint(
            """
            package io.aequicor.heartbeat.feature.chat.impl.presentation
            import io.aequicor.heartbeat.feature.chat.impl.data.ChatRepositoryImpl as Repository
            import io.aequicor.heartbeat.feature.chat.impl.data.*
            val repository: io.aequicor.heartbeat.feature.chat.impl.data.ChatRepositoryImpl? = null
            fun create() = io.aequicor.heartbeat.feature.chat.impl.data.ChatRepositoryImpl()
            """.trimIndent(),
        )
        assertEquals(4, findings.size)
    }

    @Test
    fun `presentation allows immutable annotations but not rendering or data access`() {
        assertEquals(
            2,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.presentation
            import androidx.compose.runtime.Immutable
            import androidx.compose.runtime.Stable
            import androidx.compose.runtime.Composable
            import io.aequicor.heartbeat.core.datastore.DataStores
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `ui depends on presentation and generated resources but not business contracts`() {
        assertEquals(
            3,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.ui
            import io.aequicor.heartbeat.feature.chat.impl.presentation.ChatState
            import io.aequicor.heartbeat.feature.chat.impl.resources.Res
            import io.aequicor.heartbeat.feature.chat.api.ChatIntent
            import io.aequicor.heartbeat.core.statemachine.Machine
            import io.aequicor.heartbeat.core.featuretoggles.FeatureToggleControl
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `scope markers are the only allowed reverse reference to di`() {
        assertEquals(
            1,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.presentation
            import io.aequicor.heartbeat.feature.chat.impl.di.scope.ChatScope
            import io.aequicor.heartbeat.feature.chat.impl.di.ChatGraph
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `existing pure machine contracts and data adapters are allowed`() {
        val contract = """
            package io.aequicor.heartbeat.feature.chat.api
            import io.aequicor.heartbeat.core.statemachine.machineSpec
            import io.aequicor.heartbeat.core.navigation.Route
            import kotlinx.serialization.Serializable
        """.trimIndent()
        val adapter = """
            package io.aequicor.heartbeat.feature.chat.impl.data
            import io.aequicor.heartbeat.feature.chat.impl.domain.ChatRepository
            import io.aequicor.heartbeat.core.datastore.DataStores
            import io.ktor.client.HttpClient
        """.trimIndent()
        assertTrue(rule.lint(contract).isEmpty())
        assertTrue(rule.lint(adapter).isEmpty())
    }

    @Test
    fun `unrelated infrastructure and strings describing imports are not dependencies`() {
        assertTrue(
            rule.lint(
                """
            package io.aequicor.heartbeat.core.network.impl
            import io.ktor.client.HttpClient
                """.trimIndent(),
            ).isEmpty(),
        )
        assertTrue(
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.domain
            val description = "io.ktor.client.HttpClient()"
                """.trimIndent(),
            ).isEmpty(),
        )
    }
}
