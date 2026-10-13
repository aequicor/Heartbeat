package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ServiceUiLibraryTest {
    @Test
    fun `put creates a surface at revision one and bumps the library`() {
        val result = assertIs<UiWriteResult.Applied>(UiLibrary().apply(put(content("map"))))

        val surface = result.surface!!
        assertEquals(1, surface.revision)
        assertTrue(surface.isEnabled)
        assertEquals(Author, surface.author)
        assertEquals(At, surface.createdAt)
        assertEquals(1, result.library.revision)
        assertEquals(listOf(surface), result.library.surfaces)
    }

    @Test
    fun `put replaces in place at the expected revision and keeps creation time and switch`() {
        val first = library(content("first"), content("second"))
        val disabled = applied(first.apply(UiWriteOp.SetEnabled(content("first").id, false, 1, At)))
        val later = Instant.fromEpochSeconds(At.epochSeconds + 60)

        val changed = content("first").copy(title = "Changed")
        val result = applied(disabled.apply(UiWriteOp.Put(changed, expectedRevision = 2, author = null, at = later)))

        val surface = result[changed.id]!!
        assertEquals(3, surface.revision)
        assertEquals("Changed", surface.content.title)
        assertFalse(surface.isEnabled)
        assertEquals(At, surface.createdAt)
        assertEquals(later, surface.updatedAt)
        assertNull(surface.author)
        assertEquals(listOf("first", "second"), result.surfaces.map { it.content.name.value })
    }

    @Test
    fun `put checks the expected revision`() {
        val library = library(content("map"))

        assertEquals(rejected(UiWriteRejection.Conflict), library.apply(put(content("map"))))
        assertEquals(rejected(UiWriteRejection.Conflict), library.apply(put(content("map"), expected = 2)))
        assertEquals(rejected(UiWriteRejection.Missing), library.apply(put(content("other"), expected = 1)))
    }

    @Test
    fun `writes beyond the per owner count are rejected`() {
        val full = (1..ServiceUiLimits.SURFACES_PER_OWNER).fold(UiLibrary()) { library, index ->
            applied(library.apply(put(content("s$index"))))
        }

        assertEquals(rejected(UiWriteRejection.Limit), full.apply(put(content("extra"))))
        assertIs<UiWriteResult.Applied>(full.apply(put(content("extra", owner = Other))))
    }

    @Test
    fun `writes beyond the profile count and size are rejected`() {
        val owners = (1..ServiceUiLimits.SURFACES / ServiceUiLimits.SURFACES_PER_OWNER)
            .map { UiOwner("harness", "h$it") }
        val full = owners.fold(UiLibrary()) { library, owner ->
            (1..ServiceUiLimits.SURFACES_PER_OWNER).fold(library) { inner, index ->
                applied(inner.apply(put(content("s$index", owner = owner))))
            }
        }
        val extra = content("extra", owner = UiOwner("harness", "x"))
        assertEquals(rejected(UiWriteRejection.Limit), full.apply(put(extra)))

        val large = "x".repeat(ServiceUiLimits.SPEC_BYTES)
        val owner = (0..2).map { UiOwner("harness", "big$it") }
        val fitting = ServiceUiLimits.LIBRARY_BYTES / ServiceUiLimits.SPEC_BYTES - 1
        val heavy = (0 until fitting).fold(UiLibrary()) { library, index ->
            applied(library.apply(put(content("s$index", owner = owner[index / 15], spec = large))))
        }
        assertEquals(
            rejected(UiWriteRejection.Limit),
            heavy.apply(put(content("last", owner = owner[2], spec = large))),
        )
    }

    @Test
    fun `set enabled and delete check presence and revision`() {
        val library = library(content("map"))
        val id = content("map").id
        val missing = content("other").id

        val disabled = assertIs<UiWriteResult.Applied>(library.apply(UiWriteOp.SetEnabled(id, false, 1, At)))
        assertFalse(disabled.surface!!.isEnabled)
        assertEquals(2, disabled.surface!!.revision)
        assertEquals(rejected(UiWriteRejection.Conflict), library.apply(UiWriteOp.SetEnabled(id, false, 2, At)))
        assertEquals(rejected(UiWriteRejection.Missing), library.apply(UiWriteOp.SetEnabled(missing, false, 1, At)))

        val deleted = assertIs<UiWriteResult.Applied>(library.apply(UiWriteOp.Delete(id, 1)))
        assertEquals(setOf(id), deleted.removed)
        assertTrue(deleted.library.surfaces.isEmpty())
        assertEquals(rejected(UiWriteRejection.Conflict), library.apply(UiWriteOp.Delete(id, 3)))
        assertEquals(rejected(UiWriteRejection.Missing), library.apply(UiWriteOp.Delete(missing, 1)))
    }

    @Test
    fun `purge removes every surface of the owners and leaves others`() {
        val library = library(content("a"), content("b", owner = Other), content("c"))

        val result = assertIs<UiWriteResult.Applied>(library.apply(UiWriteOp.Purge(setOf(Owner))))

        assertEquals(setOf(content("a").id, content("c").id), result.removed)
        assertEquals(listOf(content("b", owner = Other).id), result.library.surfaces.map { it.id })
        assertEquals(library.revision + 1, result.library.revision)
        val unchanged = assertIs<UiWriteResult.Applied>(result.library.apply(UiWriteOp.Purge(setOf(Owner))))
        assertEquals(result.library, unchanged.library)
        assertTrue(unchanged.removed.isEmpty())
    }

    @Test
    fun `invalid content fails validation`() {
        assertFailsWith<IllegalArgumentException> { content("map").copy(title = " ") }
        assertFailsWith<IllegalArgumentException> { content("map").copy(title = "t".repeat(81)) }
        assertFailsWith<IllegalArgumentException> { content("map").copy(launchers = setOf(UiSlot.Start)) }
        assertFailsWith<IllegalArgumentException> { content("map", spec = " ") }
        assertFailsWith<IllegalArgumentException> { content("map", spec = "x".repeat(ServiceUiLimits.SPEC_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> {
            content("map").copy(placement = UiPlacement.Card, launchers = setOf(UiSlot.Composer))
        }
        val card = content("workout").copy(placement = UiPlacement.Card)
        assertEquals(card, Json.decodeFromString<UiSurfaceContent>(Json.encodeToString(card)))
        val launched = content("map").copy(placement = UiPlacement.Panel, launchers = setOf(UiSlot.Composer))
        assertEquals(setOf(UiSlot.Composer), launched.launchers)
    }

    @Test
    fun `library round trips and never prints specs or titles`() {
        val secret = """{"version":1,"body":{"type":"text","text":"private"}}"""
        val library = library(content("map", spec = secret).copy(title = "Private title"))

        assertEquals(library, Json.decodeFromString<UiLibrary>(Json.encodeToString(library)))
        val surface = library.surfaces.single()
        for (text in listOf(library.toString(), surface.toString(), surface.content.toString())) {
            assertFalse("private" in text)
            assertFalse("Private title" in text)
        }
    }

    private fun library(vararg contents: UiSurfaceContent): UiLibrary =
        contents.fold(UiLibrary()) { library, content -> applied(library.apply(put(content))) }

    private fun applied(result: UiWriteResult): UiLibrary = assertIs<UiWriteResult.Applied>(result).library

    private fun rejected(reason: UiWriteRejection) = UiWriteResult.Rejected(reason)

    private fun put(content: UiSurfaceContent, expected: Long = 0) = UiWriteOp.Put(content, expected, Author, At)

    private fun content(name: String, owner: UiOwner = Owner, spec: String = """{"version":1}""") = UiSurfaceContent(
        owner = owner,
        name = UiSurfaceName(name),
        placement = UiPlacement.Start,
        title = "Title $name",
        spec = spec,
    )

    private companion object {
        val Owner = UiOwner("harness", "h_1")
        val Other = UiOwner("harness", "h_2")
        val Author = SessionRef(EngineId("pi"), SessionSourceId("local"), "native")
        val At: Instant = Instant.fromEpochSeconds(1_700_000_000)
    }
}
