package io.aequicor.heartbeat.feature.togglespanel.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.ToggleState
import io.aequicor.heartbeat.feature.togglespanel.api.ToggleOperation
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Immutable values required to render a flag row; [default] is the declared value the row returns to on reset. */
@Immutable
data class ToggleRowUi(
    val key: String,
    val owner: String,
    val description: String,
    val isOverridden: Boolean,
    val control: ToggleControlUi,
    val default: ToggleDefaultUi = ToggleDefaultUi.Flag(false),
)

/** Declared default value of a toggle, rendered next to the current one. */
@Immutable
sealed interface ToggleDefaultUi {
    /** Default of a boolean flag. */
    data class Flag(val isEnabled: Boolean) : ToggleDefaultUi

    /** Default option of a choice. */
    data class Choice(val value: String) : ToggleDefaultUi
}

/** Presentation-only choice of row control. */
@Immutable
sealed interface ToggleControlUi {
    /** Boolean switch state. */
    data class Flag(val isChecked: Boolean) : ToggleControlUi

    /** Selected value and immutable choices. */
    data class Choice(val value: String, val options: ImmutableList<String>) : ToggleControlUi
}

internal fun ToggleState<*>.toUi(): ToggleRowUi = ToggleRowUi(
    key = toggle.key,
    owner = toggle.owner,
    description = toggle.description,
    isOverridden = isOverridden,
    control = when (val definition = toggle) {
        is FeatureToggle.Flag -> ToggleControlUi.Flag(value == true)
        is FeatureToggle.Choice -> ToggleControlUi.Choice(value as String, definition.options.toImmutableList())
    },
    default = when (val definition = toggle) {
        is FeatureToggle.Flag -> ToggleDefaultUi.Flag(definition.default)
        is FeatureToggle.Choice -> ToggleDefaultUi.Choice(definition.default)
    },
)

internal fun TogglesPanelScreenIntent.Mutation.toOperation(state: TogglesPanelState): ToggleOperation? {
    val toggles = (state as? TogglesPanelState.Active)?.rows.orEmpty().map { it.toggle }
    return when (this) {
        is TogglesPanelScreenIntent.SetFlag -> (toggles.find { it.key == key } as? FeatureToggle.Flag)
            ?.let { ToggleOperation.SetFlag(it, isEnabled) }

        is TogglesPanelScreenIntent.SetChoice -> (toggles.find { it.key == key } as? FeatureToggle.Choice)
            ?.let { ToggleOperation.SetChoice(it, value) }

        is TogglesPanelScreenIntent.Reset -> toggles.find { it.key == key }?.let(ToggleOperation::Reset)

        TogglesPanelScreenIntent.ResetAll -> ToggleOperation.ResetAll
    }
}
