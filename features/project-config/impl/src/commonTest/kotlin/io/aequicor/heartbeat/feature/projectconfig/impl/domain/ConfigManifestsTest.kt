package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigManifestsTest {
    @Test
    fun `the current manifest is readable and others are judged by format and version`() {
        assertNull(ConfigManifests.check(ConfigManifests.current.text))
        assertNull(ConfigManifests.check("""{"format":"heartbeat-config","version":1,"generator":"x"}"""))
        assertEquals(ConfigReadFailure.Format, ConfigManifests.check("""{"format":"other","version":1}"""))
        assertEquals(ConfigReadFailure.Format, ConfigManifests.check("""{"format":"heartbeat-config","version":0}"""))
        assertEquals(ConfigReadFailure.Format, ConfigManifests.check("not json"))
        assertEquals(
            ConfigReadFailure.Version,
            ConfigManifests.check("""{"format":"heartbeat-config","version":${ConfigLayout.VERSION + 1}}"""),
        )
    }

    @Test
    fun `a tree keeps files in canonical order without the manifest`() {
        val tree = ConfigTree.of(
            listOf(ConfigManifests.current, ConfigFile(ConfigPath("b.md"), "b"), ConfigFile(ConfigPath("a.md"), "a")),
        )

        assertEquals(listOf("a.md", "b.md"), tree.files.map { it.path.value })
        assertFailsWith<IllegalArgumentException> { ConfigTree(listOf(ConfigManifests.current)) }
        assertEquals("a\nb", ConfigManifests.normalized("﻿a\r\nb"))
    }

    @Test
    fun `paths are portable unless a segment is a name Windows reserves`() {
        assertTrue(ConfigPath("harnesses/review/harness.json").isPortable)
        assertTrue(ConfigPath("learning/console.md").isPortable)
        listOf("harnesses/aux/harness.json", "learning/nul.md", "harnesses/x/COM1.kts", "learning/con.tar.md").forEach {
            assertFalse(ConfigPath(it).isPortable, it)
        }
        assertTrue(listOf(ConfigPath("learning/a.md"), ConfigPath("learning/A.md")).hasCaseClash())
        assertFalse(listOf(ConfigPath("learning/a.md"), ConfigPath("learning/b.md")).hasCaseClash())
    }
}
