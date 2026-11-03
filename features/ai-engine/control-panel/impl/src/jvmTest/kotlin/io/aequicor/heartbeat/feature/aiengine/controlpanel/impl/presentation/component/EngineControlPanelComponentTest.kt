package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.aequicor.heartbeat.core.navigation.GlobalRoutes
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavComponent
import io.aequicor.heartbeat.core.navigation.NavEntry
import io.aequicor.heartbeat.core.navigation.NavHostFactory
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.PanelsHost
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsRoute
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlIntent
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlOutput
import io.aequicor.heartbeat.feature.aiengine.controlpanel.api.EngineControlState
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.CodexId
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlPanelModel
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.EngineControlTarget
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.FakeMachine
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.managedSnapshot
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.testScopeHandle
import io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store.testStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class EngineControlPanelComponentTest {
    @Test
    fun `embedded panel close returns to blank details and preserves retained engine entries`() = runTest {
        val navigator = RecordingNavigator()
        panel(navigator, isEmbedded = true).close()

        assertEquals(0, navigator.closes)
        assertEquals(1, navigator.navigations.size)
        val navigation = navigator.navigations.single()
        assertEquals(EngineControlBlankRoute, navigation.route)
        assertEquals(
            NavOptions(launch = LaunchMode.BringToFront, transition = NavTransition.None),
            navigation.options,
        )
    }

    @Test
    fun `standalone panel close removes its navigation entry`() = runTest {
        val navigator = RecordingNavigator()
        panel(navigator, isEmbedded = false).close()

        assertEquals(1, navigator.closes)
        assertEquals(emptyList(), navigator.navigations)
    }

    @Test
    fun `connections are acquired only on explicit selection and the retained child is reused`() = runTest {
        val hosts = RecordingHosts()
        val component = panel(RecordingNavigator(), isEmbedded = true, hosts)
        assertNull(component.connections.value)
        assertEquals(0, hosts.calls)
        component.showConnections()
        val first = assertNotNull(component.connections.value)
        component.showConnections()
        assertSame(first, component.connections.value)
        assertEquals(1, hosts.calls)
        assertEquals(listOf(EngineConnectionsRoute(isEmbedded = true, engine = CodexId)), hosts.initial)
        assertEquals(GlobalRoutes.Only(setOf(EngineConnectionsRoute::class)), hosts.global)
    }

    private fun TestScope.panel(
        navigator: Navigator,
        isEmbedded: Boolean,
        hosts: NavHostFactory = RecordingHosts(),
    ): EngineControlPanelComponent {
        val model = EngineControlPanelModel(
            FakeMachine<EngineControlState, EngineControlIntent, EngineControlOutput>(managedSnapshot()),
            EngineControlTarget(CodexId),
            testScopeHandle(),
            testStoreFactory(),
        )
        return EngineControlPanelComponent(
            DefaultComponentContext(LifecycleRegistry()),
            navigator,
            isEmbedded,
            model,
            hosts,
            EngineControlTarget(CodexId),
        )
    }

    private class RecordingHosts : NavHostFactory {
        var calls = 0
        var initial: List<Route> = emptyList()
        var global: GlobalRoutes = GlobalRoutes.None

        override fun stack(
            context: ComponentContext,
            parent: Navigator,
            name: String,
            initial: List<Route>,
            local: List<RouteEntry<*>>,
            global: GlobalRoutes,
        ): StackHost {
            calls++
            this.initial = initial
            this.global = global
            return object : StackHost {
                override val navigator = parent
                override val backHandler = context.backHandler
                override val stack: Value<ChildStack<NavEntry, NavComponent>>
                    get() = error("This ownership test does not render the host")
                override fun onBack() = Unit
            }
        }

        override fun panels(
            context: ComponentContext,
            parent: Navigator,
            name: String,
            main: Route,
            details: Route?,
            local: List<RouteEntry<*>>,
        ): PanelsHost = error("Panels are not used by this component")
    }

    private class RecordingNavigator : Navigator {
        val navigations = mutableListOf<Navigation>()
        var closes = 0

        override fun navigate(route: Route, options: NavOptions) {
            navigations += Navigation(route, options)
        }

        override fun <R : Any> navigateForResult(route: Route, contract: ResultContract<R>, options: NavOptions) =
            error("Results are not used by this component")

        override fun <R : Any> results(contract: ResultContract<R>): Flow<R> = emptyFlow()

        override fun <R : Any> finishWithResult(contract: ResultContract<R>, result: R) =
            error("Results are not used by this component")

        override fun close() {
            closes++
        }
    }

    private data class Navigation(val route: Route, val options: NavOptions)
}
