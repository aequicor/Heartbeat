package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aistudio.api.StudioChatEnvironment
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.StudioEmbeds
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenAction
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioEmbedViews
import pro.respawn.flowmvi.api.PipelineContext

private typealias EmbedPipeline = PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>

/** Applies a child projection before the root can acknowledge the enclosing intent. */
internal suspend fun StudioEmbeds.handle(
    pipeline: EmbedPipeline,
    intent: AiStudioScreenIntent.Embed,
    environment: StudioChatEnvironment?,
) {
    val embeds = dispatch(intent, environment)
    pipeline.updateState { withEmbeds(embeds) }
}

/** Discards projections already superseded by dispatch or by a newer child lifecycle update. */
internal suspend fun StudioEmbeds.observe(pipeline: EmbedPipeline) {
    states.collect { embeds -> pipeline.updateState { withEmbeds(embeds) } }
}

private fun AiStudioScreenState.withEmbeds(embeds: StudioEmbedViews) =
    if (embeds.revision > remoteEmbeds.revision) copy(remoteEmbeds = embeds) else this
