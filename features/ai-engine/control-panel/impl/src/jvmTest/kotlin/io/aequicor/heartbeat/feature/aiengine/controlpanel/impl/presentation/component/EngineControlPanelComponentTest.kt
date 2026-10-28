package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.component

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.aequicor.heartbeat.core.navigation.LaunchMode
import io.aequicor.heartbeat.core.navigation.NavOptions
import io.aequicor.heartbeat.core.navigation.NavTransition
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ResultContract
import io.aequicor.heartbeat.core.navigation.Route
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

    private fun TestScope.panel(navigator: Navigator, isEmbedded: Boolean): EngineControlPanelComponent {
        val model = EngineControlPanelModel(
            FakeMachine<EngineControlState, EngineControlIntent, EngineControlOutput>(managedSnapshot()),
            EngineControlTarget(CodexId),
            testScopeHandle(),
            testStoreFactory(),
        )
        return EngineControlPanelComponent(DefaultComponentContext(LifecycleRegistry()), navigator, isEmbedded, model)
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
