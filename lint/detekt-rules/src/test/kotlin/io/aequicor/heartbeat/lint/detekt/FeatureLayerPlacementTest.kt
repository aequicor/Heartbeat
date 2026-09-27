package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class FeatureLayerPlacementTest {
    private val rule = FeatureLayerPlacement(Config.empty)

    @TempDir
    lateinit var root: Path

    @Test
    fun `di scope exception cannot hide services or graph definitions`() {
        assertEquals(0, rule.lint("package io.aequicor.heartbeat.feature.chat.impl.di.scope\ninterface ChatScope").size)
        assertEquals(1, rule.lint("package io.aequicor.heartbeat.feature.chat.impl.di.scope\nclass Service").size)
        assertEquals(
            1,
            rule.lint(
                """
            package io.aequicor.heartbeat.feature.chat.impl.di.scope
            interface ChatScope { fun repository(): Any }
                """.trimIndent(),
            ).size,
        )
    }

    @Test
    fun `changing the package cannot hide a feature source from architecture checks`() {
        val path = Path.of(
            "features/chat/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/chat/impl/domain/Model.kt",
        )
        val file = source(path, "package unrelated\nclass Model")
        assertEquals(1, rule.lint(file).size)
    }

    @Test
    fun `source directories must agree with declared layers`() {
        val path = Path.of(
            "features/chat/impl/src/commonMain/kotlin/io/aequicor/heartbeat/feature/chat/impl/data/Model.kt",
        )
        val file = source(path, "package io.aequicor.heartbeat.feature.chat.impl.domain\nclass Model")
        assertEquals(1, rule.lint(file).size)
    }

    @Test
    fun `rejects unclassified implementations and the old technical folders`() {
        listOf("", ".store", ".component", ".machine", ".util", ".resources").forEach { suffix ->
            assertEquals(1, rule.lint("package io.aequicor.heartbeat.feature.chat.impl$suffix\nclass Example").size)
        }
    }

    @Test
    fun `accepts layer subpackages contracts and infrastructure`() {
        listOf(
            "feature.chat.api",
            "feature.chat.impl.domain.repository",
            "feature.chat.impl.data",
            "feature.chat.impl.presentation.store",
            "feature.chat.impl.ui",
            "core.network.impl",
            "ds.components",
        ).forEach { suffix ->
            assertEquals(0, rule.lint("package io.aequicor.heartbeat.$suffix\nclass Example").size)
        }
    }

    @Test
    fun `nested ai engine source paths retain package validation`() {
        val path = Path.of(
            "features/ai-engine/facade/api/src/commonMain/kotlin/",
            "io/aequicor/heartbeat/feature/aiengine/facade/api/Model.kt",
        )
        assertEquals(
            0,
            rule.lint(source(path, "package io.aequicor.heartbeat.feature.aiengine.facade.api\nclass Model")).size,
        )
        assertEquals(1, rule.lint(source(path.resolveSibling("Other.kt"), "package unrelated\nclass Model")).size)
        assertEquals(1, rule.lint("package io.aequicor.heartbeat.feature.aiengine.codex.impl\nclass Runtime").size)
    }

    private fun source(relative: Path, code: String): KtFile {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, code)
        return compileForTest(file)
    }
}
