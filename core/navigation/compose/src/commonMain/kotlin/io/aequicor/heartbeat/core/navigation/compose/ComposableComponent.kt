package io.aequicor.heartbeat.core.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.NavComponent

/**
 * A [NavComponent] that renders itself. Components of feature impls implement it and delegate to the screen
 * in their `ui` package — hosts render children without a renderer registry:
 * ```
 * internal class DefaultChatRootComponent(…) : ComposableComponent, ComponentContext by context {
 *     @Composable override fun Content(modifier: Modifier) = ChatRootContent(this, modifier)
 * }
 * ```
 */
public interface ComposableComponent : NavComponent {
    /** Renders the component. */
    @Composable
    public fun Content(modifier: Modifier = Modifier)
}

@Composable
internal fun NavComponent.Render(modifier: Modifier = Modifier) {
    val component = checkNotNull(this as? ComposableComponent) {
        "${this::class} must implement ComposableComponent to be shown by a navigation host"
    }
    component.Content(modifier)
}
