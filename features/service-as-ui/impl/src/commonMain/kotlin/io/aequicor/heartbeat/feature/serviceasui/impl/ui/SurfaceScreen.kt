package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonSize
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbDialogWidth
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.hbCopyText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceState
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.Res
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_broken
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_cancel
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_close
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_dismiss
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_failed_failed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_failed_inactive
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_failed_not_found
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_failed_timed_out
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_failed_too_large
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_failed_unavailable
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_invalid_link
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_open_link
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_open_link_title
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_unsupported
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect

private val log = Log.tag("ServiceUiScreen")

/**
 * A surface drawn from its [store]: notice, content and the open modal. Links of cards without an owner are opened
 * only after the user confirms them; [onOpenSurface] opens another surface of the owner as a modal.
 */
@Composable
internal fun SurfaceScreen(
    store: Store<SurfaceState, SurfaceIntent, SurfaceAction>,
    onOpenSurface: (String, JsonObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val openSurface by rememberUpdatedState(onOpenSurface)
    var link by remember { mutableStateOf<String?>(null) }
    val state by produceState(SurfaceState(), store) {
        store.collect {
            launch {
                actions.collect { action ->
                    when (action) {
                        is SurfaceAction.OpenUrl -> if (action.isTrusted) {
                            open(uriHandler, action.url)
                        } else {
                            link = action.url
                        }

                        is SurfaceAction.Copy -> hbCopyText(clipboard, action.text)

                        is SurfaceAction.OpenSurface -> openSurface(action.name, action.args)
                    }
                }
            }
            states.collect { value = it }
        }
    }
    SurfaceContent(state, store::intent, modifier)
    link?.let { url ->
        HbDialog(
            title = stringResource(Res.string.service_ui_open_link_title),
            onDismissRequest = { link = null },
            actions = {
                HbButton(stringResource(Res.string.service_ui_cancel), { link = null }, style = HbButtonStyle.Ghost)
                HbButton(
                    stringResource(Res.string.service_ui_open_link),
                    {
                        link = null
                        open(uriHandler, url)
                    },
                )
            },
        ) { HbText(url) }
    }
}

/** Notice, content and the open modal of [state]. */
@Composable
internal fun SurfaceContent(state: SurfaceState, onIntent: (SurfaceIntent) -> Unit, modifier: Modifier = Modifier) {
    HbColumn(modifier.fillMaxWidth(), gap = HbTheme.spacing.m) {
        state.notice?.let { notice ->
            HbBanner(
                message = notice.text(),
                tone = notice.tone(),
                actions = {
                    HbButton(
                        stringResource(Res.string.service_ui_dismiss),
                        { onIntent(SurfaceIntent.DismissNotice) },
                        style = HbButtonStyle.Ghost,
                        size = HbButtonSize.Small,
                    )
                },
            )
        }
        state.root?.let { UiNodeView(it, onIntent) }
    }
    val modal = state.modal ?: return
    HbDialog(
        title = modal.title,
        onDismissRequest = { onIntent(SurfaceIntent.DismissModal) },
        width = if (modal.isWide) HbDialogWidth.Wide else HbDialogWidth.Regular,
        actions = {
            HbButton(
                stringResource(Res.string.service_ui_close),
                { onIntent(SurfaceIntent.DismissModal) },
                style = HbButtonStyle.Ghost,
            )
        },
    ) { modal.body?.let { UiNodeView(it, onIntent) } }
}

@Composable
private fun NoticeUi.text(): String = when (this) {
    is NoticeUi.Message -> message
    is NoticeUi.Failed -> stringResource(reason.text())
    NoticeUi.Unsupported -> stringResource(Res.string.service_ui_unsupported)
    NoticeUi.InvalidLink -> stringResource(Res.string.service_ui_invalid_link)
    NoticeUi.Broken -> stringResource(Res.string.service_ui_broken)
}

/** Failures of methods and of the surface are errors; refusals of this place or platform are warnings. */
private fun NoticeUi.tone(): HbTone = when (this) {
    is NoticeUi.Failed, NoticeUi.Broken -> HbTone.Danger
    is NoticeUi.Message, NoticeUi.Unsupported, NoticeUi.InvalidLink -> HbTone.Warning
}

private fun FailureUi.text(): StringResource = when (this) {
    FailureUi.Unavailable -> Res.string.service_ui_failed_unavailable
    FailureUi.NotFound -> Res.string.service_ui_failed_not_found
    FailureUi.Inactive -> Res.string.service_ui_failed_inactive
    FailureUi.TimedOut -> Res.string.service_ui_failed_timed_out
    FailureUi.Failed -> Res.string.service_ui_failed_failed
    FailureUi.TooLarge -> Res.string.service_ui_failed_too_large
}

private fun open(uriHandler: UriHandler, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The platform rejects malformed or unopenable links in different ways; the message may quote the link.
        log.w(e.redactedFailure()) { "Link could not be opened" }
    }
}
