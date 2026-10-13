package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModel
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenui.AiStudioContent
import io.aequicor.heartbeat.feature.aistudio.screenui.StudioEmbed
import io.aequicor.heartbeat.feature.aistudio.screenui.StudioExits
import pro.respawn.flowmvi.dsl.collect

/** The studio screen bound to the window's store; [AiStudioContent] draws it. */
@Composable
internal fun AiStudioScreen(
    model: AiStudioModel,
    exits: StudioExits,
    modifier: Modifier = Modifier,
    chatArea: StudioEmbed? = null,
) {
    val state by produceState(AiStudioScreenState(), model) {
        model.store.collect { states.collect { value = it } }
    }
    AiStudioContent(state, model.store::intent, exits, modifier, chatArea)
}
