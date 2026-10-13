package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_web_loading
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/** The drawable browser documents of [hosts] by route id; each host has one fixed entry. */
@Composable
internal fun rememberWebDocuments(
    hosts: StateFlow<ImmutableMap<String, StackHost>>,
): ImmutableMap<String, ComposableComponent> {
    val current by hosts.collectAsState()
    return remember(current) {
        current.mapNotNull { (id, host) ->
            (host.stack.value.active.instance as? ComposableComponent)?.let { id to it }
        }
            .toMap()
            .toImmutableMap()
    }
}

/** A document whose host is still being created: a loading state in the document's place. */
internal object OpeningDocument : ComposableComponent {
    @Composable
    override fun Content(modifier: Modifier) {
        Box(modifier, contentAlignment = Alignment.Center) {
            HbLoadingState(stringResource(Res.string.service_ui_web_loading))
        }
    }
}
