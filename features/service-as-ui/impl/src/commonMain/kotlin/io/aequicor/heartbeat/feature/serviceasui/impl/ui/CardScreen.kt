package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardPhase
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card.CardState
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.TextStyleUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_app_open
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_card_missing
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_card_unavailable
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.dsl.collect

/**
 * A card in a response, framed with its title, or a placeholder telling why it cannot be drawn; [documents] are the
 * browser documents of its body and modals by route id.
 */
@Composable
internal fun CardScreen(
    model: CardModel,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
) {
    val state by produceState(CardState(), model) { model.store.collect { states.collect { value = it } } }
    val surfaces by model.surfaces.collectAsState()
    CardView(state, surfaces, modifier, documents, onOpenApp = { model.store.intent(CardIntent.OpenApp) })
}

/** The card of [state] with its surface from [surfaces]. */
@Composable
internal fun CardView(
    state: CardState,
    surfaces: ImmutableMap<String, SurfaceModel>,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    onOpenApp: () -> Unit = {},
) {
    val placeholder = when (state.phase) {
        CardPhase.Loading -> return
        CardPhase.Missing -> stringResource(Res.string.service_ui_card_missing)
        CardPhase.Unavailable -> stringResource(Res.string.service_ui_card_unavailable)
        CardPhase.Drawn -> null
    }
    if (state.isApp && placeholder == null) {
        AppCard(state, onOpenApp, modifier)
        return
    }
    HbCard(modifier.fillMaxWidth(), contentPadding = HbTheme.spacing.l) {
        HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
            if (state.title.isNotBlank()) {
                HbText(
                    state.title,
                    Modifier.semantics { heading() },
                    style = TextStyleUi.Title.style(),
                    color = TextStyleUi.Title.color(),
                )
            }
            if (placeholder != null) {
                HbText(placeholder, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
            } else {
                state.key?.let { surfaces[it] }?.let { SurfaceScreen(it, documents = documents) }
            }
        }
    }
}

/** A compact native entry never creates a second application document or an inner scroll container. */
@Composable
private fun AppCard(state: CardState, onOpenApp: () -> Unit, modifier: Modifier = Modifier) {
    HbCard(modifier.fillMaxWidth(), contentPadding = HbTheme.spacing.l) {
        HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.l) {
            HbIcon(HbIcons.Grid, contentDescription = null, tint = HbTheme.colors.brand)
            HbColumn(Modifier.weight(1f), gap = HbTheme.spacing.xs) {
                HbText(state.title, style = HbTheme.typography.title, maxLines = 1)
                if (state.description.isNotBlank()) {
                    HbText(state.description, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
                }
            }
            HbButton(
                stringResource(Res.string.service_ui_app_open),
                onOpenApp,
                enabled = state.isOpenEnabled,
                size = HbButtonSize.Small,
            )
        }
    }
}
