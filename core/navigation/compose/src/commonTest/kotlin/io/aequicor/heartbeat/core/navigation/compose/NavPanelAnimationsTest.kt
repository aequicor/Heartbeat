// Tests the experimental ChildPanels animator selector contract of Decompose 3.5.
@file:OptIn(ExperimentalDecomposeApi::class)

package io.aequicor.heartbeat.core.navigation.compose

import com.arkivanov.decompose.Child
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.experimental.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.Direction
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Route
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class NavPanelAnimationsTest {

    private val animations = NavAnimations(fade(), fade(), fade(), fade(), predictiveBack = null)
    private val main = child("main", NavTransition.Default)

    @Test
    fun `opening details animates both panels with the requested modal animator`() {
        val details = child("details", NavTransition.Modal)
        val selected = NavPanelAnimations().select(details.configuration, animations)

        assertSame(animations.modal, selected.main(main, ChildPanelsMode.SINGLE, Direction.EXIT_BACK, false))
        assertSame(animations.modal, selected.details(details, ChildPanelsMode.SINGLE, Direction.ENTER_FRONT, false))
    }

    @Test
    fun `dismissing details keeps their transition for the returning main panel`() {
        val details = child("details", NavTransition.Fade)
        val selector = NavPanelAnimations()
        selector.select(details.configuration, animations)
        val selected = selector.select(null, animations)

        assertSame(animations.fade, selected.main(main, ChildPanelsMode.SINGLE, Direction.ENTER_BACK, false))
        assertSame(animations.fade, selected.details(details, ChildPanelsMode.SINGLE, Direction.EXIT_FRONT, false))
    }

    @Test
    fun `replacing details uses the incoming transition for both details entries`() {
        val previous = child("previous", NavTransition.Fade)
        val next = child("next", NavTransition.Modal)
        val selector = NavPanelAnimations()
        val previousSelection = selector.select(previous.configuration, animations)
        val selected = selector.select(next.configuration, animations)

        for (mode in listOf(ChildPanelsMode.SINGLE, ChildPanelsMode.DUAL)) {
            assertSame(animations.modal, selected.details(previous, mode, Direction.EXIT_BACK, false))
            assertSame(animations.modal, selected.details(next, mode, Direction.ENTER_FRONT, false))
        }
        assertSame(animations.fade, previousSelection.main(main, ChildPanelsMode.SINGLE, Direction.EXIT_BACK, false))
    }

    @Test
    fun `none disables both panels on open and dismiss`() {
        val details = child("details", NavTransition.None)
        val selector = NavPanelAnimations()
        val opening = selector.select(details.configuration, animations)
        val closing = selector.select(null, animations)

        assertNull(opening.main(main, ChildPanelsMode.SINGLE, Direction.EXIT_BACK, false))
        assertNull(opening.details(details, ChildPanelsMode.SINGLE, Direction.ENTER_FRONT, false))
        assertNull(closing.main(main, ChildPanelsMode.SINGLE, Direction.ENTER_BACK, false))
        assertNull(closing.details(details, ChildPanelsMode.SINGLE, Direction.EXIT_FRONT, false))
    }

    @Test
    fun `dual mode keeps main stationary while details use the expand animator`() {
        val details = child("details", NavTransition.Expand("preview"))
        val selected = NavPanelAnimations().select(details.configuration, animations)

        assertNull(selected.main(main, ChildPanelsMode.DUAL, Direction.ENTER_BACK, false))
        assertSame(animations.expand, selected.details(details, ChildPanelsMode.DUAL, Direction.ENTER_FRONT, false))
    }

    @Test
    fun `changing the supplied animations updates the next panel transition`() {
        val details = child("details", NavTransition.Default)
        val selector = NavPanelAnimations()
        selector.select(details.configuration, animations)
        val updated = NavAnimations(fade(), fade(), fade(), fade(), predictiveBack = null)
        val selected = selector.select(details.configuration, updated)

        assertSame(updated.default, selected.main(main, ChildPanelsMode.SINGLE, Direction.EXIT_BACK, false))
        assertSame(updated.default, selected.details(details, ChildPanelsMode.DUAL, Direction.ENTER_FRONT, false))
    }

    private fun child(id: String, transition: NavTransition): Child.Created<NavEntry, NavComponent> =
        Child.Created(TestEntry(id, transition), object : NavComponent {})

    private data class TestEntry(override val id: String, override val transition: NavTransition) : NavEntry {
        override val route: Route = object : Route {}
    }
}
