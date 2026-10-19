package io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec

import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LifecycleCardTest {
    @Test
    fun `lifecycle card and quick actions match the real script methods`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve(".heartbeat")) }
        val source = Files.readString(root.resolve(".heartbeat/harnesses/product_lifecycle/board.kts"))
        val methods = Regex("""ui\.method\(ItemName\("([a-z_]+)"\)""").findAll(source).map { it.groupValues[1] }.toSet()
        val text = Files.readString(root.resolve(".heartbeat/interfaces/product_lifecycle/lifecycle_board.spec.json"))
        val result = checkUiSpec(text, UiSpecRules(UiSpecOrigin.Library, UiPlacement.Card, methods))
        val valid = assertIs<UiSpecCheck.Valid>(result, result.toString())
        assertEquals(emptyList(), valid.warnings)
        assertEquals(6, valid.spec.quickActions.size)
    }
}
