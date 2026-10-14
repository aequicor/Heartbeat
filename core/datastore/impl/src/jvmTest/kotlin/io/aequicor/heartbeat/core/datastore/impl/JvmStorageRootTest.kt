package io.aequicor.heartbeat.core.datastore.impl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmStorageRootTest {
    private val home = File(System.getProperty("java.io.tmpdir"), "heartbeat-user")

    @Test
    fun `an absolute XDG data home keeps application data in the configured directory`() {
        val dataHome = File(home, "application-data")

        assertEquals(File(dataHome, "heartbeat"), linuxStorageDirectory(home.path, dataHome.path))
    }

    @Test
    fun `unset blank and relative XDG data homes use the standard user directory`() {
        listOf(null, "", " ", "local-data", "./data", "../data").forEach { dataHome ->
            assertEquals(
                File(home, ".local/share/heartbeat"),
                linuxStorageDirectory(home.path, dataHome),
                "XDG_DATA_HOME=$dataHome",
            )
        }
    }
}
