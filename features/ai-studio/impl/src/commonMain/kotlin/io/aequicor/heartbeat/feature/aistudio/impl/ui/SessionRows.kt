package io.aequicor.heartbeat.feature.aistudio.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbMenuButton
import io.aequicor.heartbeat.ds.components.HbMenuItem
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.RenameUi
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.SessionUi
import io.aequicor.heartbeat.feature.aistudio.impl.resources.Res
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_archive
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_mark_read
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_mark_unread
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_open_beside
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_pin
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_rename
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_restore
import io.aequicor.heartbeat.feature.aistudio.impl.resources.action_unpin
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_actions
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_branch
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_running
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_title_field
import io.aequicor.heartbeat.feature.aistudio.impl.resources.session_unread
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * Parameters shared by every session row of the sidebar. A session may be listed in several sections, so
 * menus and title fields belong to a row key (`section:id`): [openMenu] is the row whose menu is shown.
 */
internal class SessionRows(
    private val selectedId: String?,
    private val renaming: RenameUi?,
    private val openMenu: String?,
    private val onMenu: (String?) -> Unit,
    private val isOpenBesideAllowed: Boolean,
    private val onIntent: (AiStudioScreenIntent) -> Unit,
) {
    /** A session row of [section], or its title field while it is being renamed from this row. */
    @Composable
    fun SessionRow(session: SessionUi, section: String, modifier: Modifier = Modifier, level: Int = 0) {
        val rowKey = "$section:${session.id}"
        if (renaming?.origin == rowKey) {
            RenameField(renaming.title, onIntent, modifier.padding(start = HbTheme.spacing.xl * level))
            return
        }
        val status = sessionStatus(session)
        val isMenuOpen = openMenu == rowKey
        HbNavigationItem(
            label = session.title,
            onClick = { onIntent(AiStudioScreenIntent.OpenSession(session.id)) },
            modifier = modifier.testTag("session-${session.id}").semantics {
                if (status.isNotEmpty()) stateDescription = status
            },
            isSelected = session.id == selectedId,
            isEmphasized = session.isUnread,
            level = level,
        ) { isActive ->
            if (isActive || isMenuOpen) {
                HbMenuButton(
                    icon = HbIcons.More,
                    contentDescription = stringResource(Res.string.session_actions),
                    items = sessionMenu(session, isOpenBesideAllowed),
                    isExpanded = isMenuOpen,
                    onExpandedChange = { onMenu(if (it) rowKey else null) },
                    onItem = { sessionAction(session, it, rowKey)?.let(onIntent) },
                    modifier = Modifier.testTag("session-menu-$rowKey"),
                )
            } else {
                SessionIndicator(session)
            }
        }
    }
}

@Composable
private fun SessionIndicator(session: SessionUi) {
    val dimensions = HbTheme.dimensions
    Box(Modifier.size(dimensions.touchTarget), contentAlignment = Alignment.Center) {
        when {
            session.isRunning -> HbActivityIndicator()

            session.isUnread -> Box(
                Modifier.size(dimensions.statusDotSize).background(HbTheme.colors.brand, CircleShape),
            )

            session.branch != null -> HbIcon(HbIcons.Branch, null, Modifier.size(dimensions.iconSmallSize))

            session.isPinned -> HbIcon(HbIcons.Pin, null, Modifier.size(dimensions.iconSmallSize))
        }
    }
}

@Composable
private fun sessionStatus(session: SessionUi): String = listOfNotNull(
    stringResource(Res.string.session_running).takeIf { session.isRunning },
    stringResource(Res.string.session_unread).takeIf { session.isUnread },
    session.branch?.let { stringResource(Res.string.session_branch, it) },
).joinToString(", ")

@Composable
internal fun sessionMenu(session: SessionUi, isOpenBesideAllowed: Boolean): ImmutableList<HbMenuItem> = listOfNotNull(
    HbMenuItem(MENU_RENAME, stringResource(Res.string.action_rename), HbIcons.Edit, isFocusRestoredOnSelect = false),
    if (session.isArchived) {
        null
    } else if (session.isPinned) {
        HbMenuItem(MENU_PIN, stringResource(Res.string.action_unpin), HbIcons.Unpin)
    } else {
        HbMenuItem(MENU_PIN, stringResource(Res.string.action_pin), HbIcons.Pin)
    },
    HbMenuItem(
        id = MENU_UNREAD,
        label = stringResource(if (session.isUnread) Res.string.action_mark_read else Res.string.action_mark_unread),
        icon = HbIcons.Mail,
    ),
    HbMenuItem(MENU_BESIDE, stringResource(Res.string.action_open_beside), HbIcons.Grid).takeIf { isOpenBesideAllowed },
    if (session.isArchived) {
        HbMenuItem(MENU_ARCHIVE, stringResource(Res.string.action_restore), HbIcons.Undo, isGroupStart = true)
    } else {
        HbMenuItem(MENU_ARCHIVE, stringResource(Res.string.action_archive), HbIcons.Archive, isGroupStart = true)
    },
).toImmutableList()

/** The screen intent of a [sessionMenu] item; `null` for an unknown id. */
internal fun sessionAction(session: SessionUi, id: String, origin: String): AiStudioScreenIntent? = when (id) {
    MENU_RENAME -> AiStudioScreenIntent.StartRename(session.id, origin)
    MENU_PIN -> AiStudioScreenIntent.SetPinned(session.id, !session.isPinned)
    MENU_UNREAD -> AiStudioScreenIntent.SetUnread(session.id, !session.isUnread)
    MENU_BESIDE -> AiStudioScreenIntent.OpenBeside(session.id)
    MENU_ARCHIVE -> AiStudioScreenIntent.SetArchived(session.id, !session.isArchived)
    else -> null.also { log.w { "unknown session action ignored" } }
}

private val log = Log.tag("StudioSessionRows")

@Composable
internal fun RenameField(title: String, onIntent: (AiStudioScreenIntent) -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    var hasHadFocus by remember { mutableStateOf(false) }
    SideEffect(focus) { focus.requestFocus() }
    HbTextField(
        value = title,
        onValueChange = { onIntent(AiStudioScreenIntent.RenameChanged(it)) },
        modifier = modifier.fillMaxWidth().focusRequester(focus)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hasHadFocus = true
                } else if (hasHadFocus) {
                    onIntent(AiStudioScreenIntent.CommitRename)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> onIntent(AiStudioScreenIntent.CommitRename)
                    Key.Escape -> onIntent(AiStudioScreenIntent.CancelRename)
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .testTag("session-rename"),
        accessibleLabel = stringResource(Res.string.session_title_field),
    )
}

private const val MENU_RENAME = "rename"
private const val MENU_PIN = "pin"
private const val MENU_UNREAD = "unread"
private const val MENU_BESIDE = "beside"
private const val MENU_ARCHIVE = "archive"
