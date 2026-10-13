package io.aequicor.heartbeat.feature.aistudio.screenui

import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.ModelUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** The demo models of the scripted agent as the store shows them (`StudioModels` of ai-studio:impl). */
internal val StudioModelOptions: ImmutableList<ModelUi> = persistentListOf(
    ModelUi("pulse-pro", "Pulse Pro", isLocalProjectSupported = true, isScripted = true),
    ModelUi("pulse", "Pulse", isLocalProjectSupported = true, isScripted = true),
    ModelUi("pulse-mini", "Pulse Mini", isLocalProjectSupported = true, isScripted = true),
)
