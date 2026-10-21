package io.aequicor.heartbeat.feature.aistudio.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_busy
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_disabled
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_pending
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_stale
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_unavailable
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_unsupported
import io.aequicor.heartbeat.feature.aistudio.impl.resources.engine_switch_workspace
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchFailureUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.EngineSwitchUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ModelUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.OrganismUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PRIMARY_SUB_SESSION
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionConfigurationUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SessionUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.selectedNative
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/** The same-binding model picker keeps its native semantics; replacement routes carry explicit refusals. */
internal fun AiStudioScreenState.modelsForPane(
    pane: PaneUi,
    session: SessionUi?,
    configuration: SessionConfigurationUi?,
    organism: OrganismUi?,
): ImmutableList<ModelUi> {
    val modelId = organism?.modelId ?: configuration?.modelId ?: session?.modelId?.takeIf(String::isNotBlank)
    val project = pane.projectId ?: session?.projectId
    if (!canOfferEngineSwitch(session, organism) || selectedNative(session?.id) != PRIMARY_SUB_SESSION) {
        return modelsForProject(project, modelId)
    }
    checkNotNull(session)
    val current = models.firstOrNull { it.id == modelId }
    val route = configuration?.engineRoute ?: current?.engineRoute
    val isBusy = isEngineSwitchBusy(session, configuration)
    val nativeModels = modelsForProject(project, modelId)
    return models.mapNotNull { model ->
        if (model.id == modelId || (route != null && model.connectionKey == route)) {
            nativeModels.firstOrNull { it.id == model.id }
        } else {
            model.copy(
                switchFailure = model.routeFailure(isBusy, project ?: session.executionWorkspace, session.isHelper),
            )
        }
    }.toImmutableList()
}

private fun AiStudioScreenState.canOfferEngineSwitch(session: SessionUi?, organism: OrganismUi?): Boolean =
    isEngineSwitchingAvailable && session != null && !session.isOrganism && organism == null

private fun AiStudioScreenState.isEngineSwitchBusy(
    session: SessionUi,
    configuration: SessionConfigurationUi?,
): Boolean = session.isRunning || session.id in stopping || configuration?.pendingOperation != null ||
    engineSwitches[session.id]?.isPending == true || !session.isContinuable

private fun ModelUi.routeFailure(busy: Boolean, project: String?, isHelper: Boolean): EngineSwitchFailureUi? = when {
    busy -> EngineSwitchFailureUi.Busy
    engineRoute == null -> EngineSwitchFailureUi.Unsupported
    project != null && !isLocalProjectSupported -> EngineSwitchFailureUi.Workspace
    isHelper && !isTrustSupported -> EngineSwitchFailureUi.Unsupported
    else -> null
}

@Composable
internal fun engineSwitchReason(reason: EngineSwitchFailureUi): String = stringResource(
    when (reason) {
        EngineSwitchFailureUi.Disabled -> Res.string.engine_switch_disabled
        EngineSwitchFailureUi.Busy -> Res.string.engine_switch_busy
        EngineSwitchFailureUi.Stale -> Res.string.engine_switch_stale
        EngineSwitchFailureUi.Unsupported -> Res.string.engine_switch_unsupported
        EngineSwitchFailureUi.Unavailable -> Res.string.engine_switch_unavailable
        EngineSwitchFailureUi.Workspace -> Res.string.engine_switch_workspace
    },
)

@Composable
internal fun EngineSwitchNotice(state: EngineSwitchUi?, modifier: Modifier = Modifier) {
    val failure = state?.failure
    val message = when {
        state?.isPending == true -> stringResource(Res.string.engine_switch_pending)
        failure != null -> engineSwitchReason(failure)
        else -> return
    }
    HbBanner(
        message,
        modifier.testTag("engine-switch-notice"),
        tone = if (failure != null) HbTone.Danger else HbTone.Neutral,
    )
}

/** The plan dialog keeps the pre-switch model filter, independent of composer replacement offers. */
internal fun AiStudioScreenState.planModelsForPane(
    pane: PaneUi,
    session: SessionUi?,
    configuration: SessionConfigurationUi?,
    organism: OrganismUi?,
): ImmutableList<ModelUi> = modelsForProject(
    pane.projectId ?: session?.projectId ?: session?.executionWorkspace,
    organism?.modelId ?: configuration?.modelId ?: session?.modelId?.takeIf(String::isNotBlank),
)
