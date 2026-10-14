package io.aequicor.heartbeat.feature.serviceasui.impl.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.navigation.compose.ComposableComponent
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
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiNodeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.FailureUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.NoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceAction
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
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
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_file_failed
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_invalid_link
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_open_link
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_open_link_title
import io.aequicor.heartbeat.feature.serviceasui.impl.resources.service_ui_unsupported
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect

private val log = Log.tag("ServiceUiScreen")

/**
 * The surface of [model] with the browser documents its `web` nodes show: [documents] are the documents its host
 * embeds, by route id. A document the store opened before its host exists draws a loading state at its size, so the
 * node neither flashes a note nor jumps in height.
 */
@Composable
internal fun SurfaceScreen(
    model: SurfaceModel,
    modifier: Modifier = Modifier,
    documents: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    isApp: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    isContentFill: Boolean = true,
    isBodyInset: Boolean = false,
) {
    val shown by model.webDocuments.collectAsState()
    val webs = remember(shown, documents) {
        shown.mapValues { (_, id) -> documents[id] ?: OpeningDocument }.toImmutableMap()
    }
    SurfaceScreen(model.store, modifier, webs, isApp, scrollState, isContentFill, isBodyInset)
}

/**
 * A surface drawn from its [store]: notice, content and the open modal, with [webs] for its `web` nodes by node key.
 * Links of cards without an owner are opened only after the user confirms them.
 */
@Composable
internal fun SurfaceScreen(
    store: Store<SurfaceState, SurfaceIntent, SurfaceAction>,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    isApp: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    isContentFill: Boolean = true,
    isBodyInset: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
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
                    }
                }
            }
            states.collect { value = it }
        }
    }
    SurfaceContent(state, store::intent, modifier, webs, isApp, scrollState, isContentFill, isBodyInset)
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

/** Notice, content and the open modal of [state]; `web` nodes draw [webs] by node key. */
@Composable
internal fun SurfaceContent(
    state: SurfaceState,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    isApp: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    isContentFill: Boolean = true,
    isBodyInset: Boolean = false,
) {
    val inset = if (isBodyInset) HbTheme.spacing.xl else HbTheme.spacing.none
    HbColumn(modifier.fillMaxWidth().padding(inset), gap = HbTheme.spacing.m) {
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
        state.root?.let { root ->
            val body = if (isApp) Modifier.weight(1f, fill = isContentFill).fillMaxWidth() else Modifier
            SurfaceBody(root, onIntent, body, webs, isApp, scrollState, isContentFill)
        }
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
    ) { modal.body?.let { UiNodeView(it, onIntent, webs = webs) } }
}

@Composable
private fun SurfaceBody(
    root: UiNodeUi,
    onIntent: (SurfaceIntent) -> Unit,
    modifier: Modifier = Modifier,
    webs: ImmutableMap<String, ComposableComponent> = persistentMapOf(),
    isApp: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
    isContentFill: Boolean = true,
) {
    if (isApp && root.containsWeb()) {
        AppNodeView(root, onIntent, modifier, webs, isContentFill)
    } else {
        UiNodeView(root, onIntent, if (isApp) modifier.hbVerticalScroll(scrollState) else modifier, webs)
    }
}

@Composable
private fun NoticeUi.text(): String = when (this) {
    is NoticeUi.Message -> message
    is NoticeUi.Failed -> stringResource(reason.text())
    NoticeUi.Unsupported -> stringResource(Res.string.service_ui_unsupported)
    NoticeUi.InvalidLink -> stringResource(Res.string.service_ui_invalid_link)
    NoticeUi.FileFailed -> stringResource(Res.string.service_ui_file_failed)
    NoticeUi.Broken -> stringResource(Res.string.service_ui_broken)
}

/** Failures of methods and of the surface are errors; refusals of this place or platform are warnings. */
private fun NoticeUi.tone(): HbTone = when (this) {
    is NoticeUi.Failed, NoticeUi.FileFailed, NoticeUi.Broken -> HbTone.Danger
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
