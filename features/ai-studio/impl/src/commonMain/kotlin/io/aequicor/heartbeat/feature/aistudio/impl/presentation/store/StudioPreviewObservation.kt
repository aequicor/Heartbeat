package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceRef
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAttachmentPreviews
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import pro.respawn.flowmvi.api.PipelineContext

/** Cancels outstanding preview reads when their rows disappear or the screen unsubscribes. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun observeAttachmentPreviews(
    pipeline: PipelineContext<AiStudioScreenState, AiStudioScreenIntent, AiStudioScreenAction>,
    previews: StudioAttachmentPreviews,
    requests: Flow<List<ResourceRef>>,
) = with(pipeline) {
    requests.distinctUntilChanged().flatMapLatest(previews::observe).collect { values ->
        updateState {
            copy(
                attachmentPreviews = values.mapKeys { (id, _) -> id.removePrefix("attachment:") }
                    .mapValues { (_, preview) -> preview.toUi() }.toImmutableMap(),
            )
        }
    }
}
