package io.aequicor.heartbeat.feature.aiengine.connections.impl.data

import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopRuntimeFilePickerTest {
    @Test
    fun `picker returns one selected file and rejects ambiguous or cancelled selections`() = runTest {
        val dialogs = FakeDialogs()
        val picker = DesktopRuntimeFilePicker(dialogs)

        dialogs.selection = listOf("/opt/runtime")
        assertEquals("/opt/runtime", picker.pick())
        assertFalse(dialogs.multiple)
        dialogs.selection = emptyList()
        assertNull(picker.pick())
        dialogs.selection = listOf("/opt/first", "/opt/second")
        assertNull(picker.pick())
    }
}

private class FakeDialogs : DesktopFileDialogs {
    var selection = emptyList<String>()
    var multiple = true
    override suspend fun pickDirectory(title: String?): String? = error("Unexpected folder picker")
    override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> {
        multiple = allowMultiple
        return selection
    }
    override suspend fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String? =
        error("Unexpected save picker")
}
