package io.aequicor.heartbeat.feature.githubconnector.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbBanner
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbDialog
import io.aequicor.heartbeat.ds.components.HbIconButton
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbLoadingState
import io.aequicor.heartbeat.ds.components.HbPaneHeader
import io.aequicor.heartbeat.ds.components.HbSearchField
import io.aequicor.heartbeat.ds.components.HbSettingsRow
import io.aequicor.heartbeat.ds.components.HbSettingsSection
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectionUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectorScreenIntent
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubConnectorScreenState
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubDialogUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubFailureUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubRepositoryUi
import io.aequicor.heartbeat.feature.githubconnector.impl.presentation.GitHubStepUi
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.Res
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_account
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_back
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_card_description
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_card_title
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_change
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_connect
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_connected
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_description
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_account
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_cancel
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_clear
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_confirm
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_discovering
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_loading_repositories
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_no_repositories
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_nothing_found
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_repository
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_search
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_title
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_dialog_verifying
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_disconnect
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_failure_cli_not_found
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_failure_failed
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_failure_no_access
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_failure_no_pull_request_access
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_failure_not_logged_in
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_loading
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_not_connected
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_note
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_repository
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_repository_private
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_repository_public
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_retry
import io.aequicor.heartbeat.feature.githubconnector.impl.resources.github_title
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The GitHub connector of Heartbeat tools: one card, one button and a dialog that picks account and repository. */
@Composable
internal fun GitHubConnectorContent(
    state: GitHubConnectorScreenState,
    onIntent: (GitHubConnectorScreenIntent) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbColumn(
        modifier.fillMaxSize().background(HbTheme.surfaces.backdrop).testTag("github-connector"),
        gap = HbTheme.spacing.none,
    ) {
        if (onBack != null) {
            HbPaneHeader(
                stringResource(Res.string.github_title),
                navigation = { HbIconButton(HbIcons.ArrowLeft, stringResource(Res.string.github_back), onBack) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            HbLazyColumn(
                Modifier.widthIn(max = HbTheme.dimensions.settingsMaxWidth).fillMaxSize(),
                gap = HbTheme.spacing.l,
                contentPadding = PaddingValues(HbTheme.spacing.xl),
            ) {
                item(key = "description") {
                    HbText(
                        stringResource(Res.string.github_description),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
                item(key = "card") { ConnectorCard(state, onIntent) }
                item(key = "note") {
                    HbText(
                        stringResource(Res.string.github_note),
                        style = HbTheme.typography.caption,
                        color = HbTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
    state.dialog?.let { ConnectDialog(it, state, onIntent) }
}

@Composable
private fun ConnectorCard(state: GitHubConnectorScreenState, onIntent: (GitHubConnectorScreenIntent) -> Unit) {
    val connection = state.connection
    HbSettingsSection(
        stringResource(Res.string.github_card_title),
        Modifier.testTag("github-card"),
        description = stringResource(Res.string.github_card_description),
        trailingContent = {
            when {
                state.isLoading -> Unit

                connection == null -> HbButton(
                    stringResource(Res.string.github_connect),
                    { onIntent(GitHubConnectorScreenIntent.Connect) },
                    Modifier.testTag("github-connect"),
                )

                else -> {
                    HbButton(
                        stringResource(Res.string.github_change),
                        { onIntent(GitHubConnectorScreenIntent.Connect) },
                        Modifier.testTag("github-change"),
                        style = HbButtonStyle.Secondary,
                    )
                    HbButton(
                        stringResource(Res.string.github_disconnect),
                        { onIntent(GitHubConnectorScreenIntent.Disconnect) },
                        Modifier.testTag("github-disconnect"),
                        style = HbButtonStyle.Ghost,
                    )
                }
            }
        },
    ) {
        state.failure?.let { failure ->
            HbBanner(stringResource(failure.message()), Modifier.testTag("github-failure")) {
                HbButton(
                    stringResource(Res.string.github_retry),
                    { onIntent(GitHubConnectorScreenIntent.Retry) },
                    Modifier.testTag("github-retry"),
                    style = HbButtonStyle.Secondary,
                )
            }
        }
        when {
            state.isLoading -> HbLoadingState(stringResource(Res.string.github_loading))

            connection == null -> HbText(
                stringResource(Res.string.github_not_connected),
                style = HbTheme.typography.label,
                color = HbTheme.colors.textSecondary,
                modifier = Modifier.testTag("github-empty"),
            )

            else -> ConnectionRows(connection)
        }
    }
}

@Composable
private fun ConnectionRows(connection: GitHubConnectionUi) {
    HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.none) {
        HbSettingsRow(
            stringResource(Res.string.github_account),
            Modifier.testTag("github-account"),
            trailingContent = { HbText(connection.account, style = HbTheme.typography.label, maxLines = 1) },
        )
        HbSettingsRow(
            stringResource(Res.string.github_repository),
            Modifier.testTag("github-repository"),
            trailingContent = {
                HbBadge(stringResource(Res.string.github_connected), tone = HbTone.Success)
                HbText(
                    connection.repository,
                    Modifier.weight(1f, fill = false),
                    HbTheme.typography.label,
                    maxLines = 1,
                )
            },
        )
    }
}

@Composable
private fun ConnectDialog(
    dialog: GitHubDialogUi,
    state: GitHubConnectorScreenState,
    onIntent: (GitHubConnectorScreenIntent) -> Unit,
) {
    HbDialog(
        stringResource(Res.string.github_dialog_title),
        onDismissRequest = { onIntent(GitHubConnectorScreenIntent.Cancel) },
        modifier = Modifier.testTag("github-dialog"),
        actions = {
            HbButton(
                stringResource(Res.string.github_dialog_cancel),
                { onIntent(GitHubConnectorScreenIntent.Cancel) },
                Modifier.testTag("github-cancel"),
                style = HbButtonStyle.Secondary,
            )
            HbButton(
                stringResource(Res.string.github_dialog_confirm),
                { onIntent(GitHubConnectorScreenIntent.Confirm) },
                Modifier.testTag("github-confirm"),
                enabled = dialog.canConfirm,
            )
        },
    ) {
        dialog.failure?.let { HbBanner(stringResource(it.message()), Modifier.testTag("github-dialog-failure")) }
        progress(dialog.step)?.let { HbLoadingState(stringResource(it), Modifier.testTag("github-progress")) }
        if (dialog.accounts.isNotEmpty()) AccountChoices(dialog, onIntent)
        if (dialog.step == GitHubStepUi.ChoosingRepository || dialog.step == GitHubStepUi.Verifying) {
            RepositoryChoices(dialog, state, onIntent)
        }
    }
}

@Composable
private fun AccountChoices(dialog: GitHubDialogUi, onIntent: (GitHubConnectorScreenIntent) -> Unit) {
    val isBusy = dialog.step == GitHubStepUi.Verifying
    HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbText(
            stringResource(Res.string.github_dialog_account),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        dialog.accounts.forEach { account ->
            HbChoiceRow(
                account,
                selected = account == dialog.account,
                isMultiple = false,
                onClick = { onIntent(GitHubConnectorScreenIntent.SelectAccount(account)) },
                modifier = Modifier.testTag("github-account-$account"),
                enabled = !isBusy,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RepositoryChoices(
    dialog: GitHubDialogUi,
    state: GitHubConnectorScreenState,
    onIntent: (GitHubConnectorScreenIntent) -> Unit,
) {
    val isBusy = dialog.step == GitHubStepUi.Verifying
    HbColumn(Modifier.fillMaxWidth(), gap = HbTheme.spacing.xs) {
        HbText(
            stringResource(Res.string.github_dialog_repository),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
        HbSearchField(
            state.query,
            { onIntent(GitHubConnectorScreenIntent.Query(it)) },
            stringResource(Res.string.github_dialog_search),
            stringResource(Res.string.github_dialog_clear),
            Modifier.fillMaxWidth().testTag("github-search"),
            enabled = !isBusy,
        )
        if (dialog.repositories.isEmpty() || state.visibleRepositories.isEmpty()) {
            HbText(
                stringResource(
                    if (dialog.repositories.isEmpty()) {
                        Res.string.github_dialog_no_repositories
                    } else {
                        Res.string.github_dialog_nothing_found
                    },
                ),
                style = HbTheme.typography.caption,
                color = HbTheme.colors.textSecondary,
                modifier = Modifier.testTag("github-no-repositories"),
            )
        }
        state.visibleRepositories.forEach { repository ->
            HbChoiceRow(
                repository.label(),
                selected = repository.name == dialog.repository,
                isMultiple = false,
                onClick = { onIntent(GitHubConnectorScreenIntent.SelectRepository(repository.name)) },
                modifier = Modifier.testTag("github-repo-${repository.name}"),
                enabled = !isBusy,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GitHubRepositoryUi.label(): String {
    val visibility = if (isPrivate) Res.string.github_repository_private else Res.string.github_repository_public
    return "$name · " + stringResource(visibility)
}

private fun progress(step: GitHubStepUi): StringResource? = when (step) {
    GitHubStepUi.Discovering -> Res.string.github_dialog_discovering
    GitHubStepUi.LoadingRepositories -> Res.string.github_dialog_loading_repositories
    GitHubStepUi.Verifying -> Res.string.github_dialog_verifying
    GitHubStepUi.ChoosingAccount, GitHubStepUi.ChoosingRepository -> null
}

private fun GitHubFailureUi.message(): StringResource = when (this) {
    GitHubFailureUi.CliNotFound -> Res.string.github_failure_cli_not_found
    GitHubFailureUi.NotLoggedIn -> Res.string.github_failure_not_logged_in
    GitHubFailureUi.NoAccess -> Res.string.github_failure_no_access
    GitHubFailureUi.NoPullRequestAccess -> Res.string.github_failure_no_pull_request_access
    GitHubFailureUi.Failed -> Res.string.github_failure_failed
}

@Preview
@Composable
private fun GitHubConnectorLightPreview() {
    HbTheme(darkTheme = false) { GitHubConnectorContent(GitHubConnectorScreenState(isLoading = false), {}, null) }
}

@Preview
@Composable
private fun GitHubConnectorDarkPreview() {
    HbTheme(darkTheme = true) {
        GitHubConnectorContent(
            GitHubConnectorScreenState(
                isLoading = false,
                connection = GitHubConnectionUi("aequicor", "aequicor/Heartbeat"),
                failure = GitHubFailureUi.NoAccess,
            ),
            {},
            null,
        )
    }
}

@Preview
@Composable
private fun GitHubConnectorDialogPreview() {
    val dialog = GitHubDialogUi(
        step = GitHubStepUi.ChoosingRepository,
        accounts = persistentListOf("aequicor", "work-account"),
        account = "aequicor",
        repositories = persistentListOf(
            GitHubRepositoryUi("aequicor/Heartbeat", false),
            GitHubRepositoryUi("aequicor/a-very-long-repository-name-that-does-not-fit-in-the-dialog", true),
        ),
        repository = "aequicor/Heartbeat",
        failure = GitHubFailureUi.NoPullRequestAccess,
    )
    HbTheme(darkTheme = false) {
        GitHubConnectorContent(GitHubConnectorScreenState(isLoading = false, dialog = dialog), {}, null)
    }
}

@Preview
@Composable
private fun GitHubConnectorConnectedPreview() {
    HbTheme(darkTheme = false) {
        GitHubConnectorContent(
            GitHubConnectorScreenState(
                isLoading = false,
                connection = GitHubConnectionUi("aequicor", "aequicor/a-very-long-repository-name-in-the-card"),
            ),
            {},
            null,
        )
    }
}
