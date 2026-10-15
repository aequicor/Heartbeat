package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiPlacement
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.Other
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.PrMap
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.place.surface
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AppSelectionTest {
    private val other = surface("other", UiPlacement.Card, Other, at = 10)
    private val library = UiLibrary(listOf(AppSurface, other))
    private val args = buildJsonObject { put("workout", "persisted") }
    private val card = UiCard(
        "saved",
        AppChat,
        "call",
        "Gym",
        UiCardSource.Surface(AppSurface.id),
        args,
        Instant.fromEpochSeconds(0),
    )

    @Test
    fun `durable receipt wins over newer apps with exact saved args and has no inactive fallback`() {
        val selected = selectApp(library, AppChatContext, setOf(PrMap, Other), card)!!
        assertEquals(AppSurface, selected.surface)
        assertEquals(args, selected.target.args)
        assertEquals("saved", selected.target.cardId)
        assertNull(selectApp(library, AppChatContext, setOf(Other), card))
        assertNull(selectApp(UiLibrary(listOf(other)), AppChatContext, setOf(PrMap, Other), card))
        assertNull(selectApp(library, AppStart, setOf(PrMap), card))
    }

    @Test
    fun `only a preselected unchanged app entering its first chat may keep the old model`() {
        val start = selectApp(library, AppStart, setOf(PrMap), null)!!
        val chat = selectApp(library, AppChatContext, setOf(PrMap), null)!!
        assertTrue(start.canContinueAs(chat))
        assertFalse(chat.canContinueAs(start))
        assertFalse(
            start.copy(context = AppChatContext.copy(call = AppStart.call.copy(preselected = emptyMap())))
                .canContinueAs(chat),
        )
        assertFalse(start.canContinueAs(chat.copy(target = chat.target.copy(args = args))))
        assertFalse(start.canContinueAs(chat.copy(surface = other)))
    }
}
