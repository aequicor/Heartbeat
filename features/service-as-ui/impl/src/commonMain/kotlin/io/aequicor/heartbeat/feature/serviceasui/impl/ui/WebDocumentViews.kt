package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.aequicor.heartbeat.core.navigation.StackHost
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.StateFlow

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
