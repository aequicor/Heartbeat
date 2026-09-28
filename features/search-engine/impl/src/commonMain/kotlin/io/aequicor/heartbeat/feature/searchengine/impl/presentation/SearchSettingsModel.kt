package io.aequicor.heartbeat.feature.searchengine.impl.presentation

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.secrets.Secret
import io.aequicor.heartbeat.feature.searchengine.api.SearchConfiguration
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.api.SearchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.plugins.reduce

internal enum class CheckPhase { Idle, Checking, Success, Failure }

/** Screen state excludes credentials. Input keys exist only in the composable until submitted. */
internal data class SearchSettingsState(
    val settings: SearchSettings? = null,
    val searchHost: String = "",
    val contentsHost: String = "",
    val searchCheck: CheckPhase = CheckPhase.Idle,
    val contentsCheck: CheckPhase = CheckPhase.Idle,
    val searchCheckFailure: SearchFailure? = null,
    val contentsCheckFailure: SearchFailure? = null,
    val failure: SearchFailure? = null,
) : MVIState

internal sealed interface SearchSettingsIntent : MVIIntent {
    data object Load : SearchSettingsIntent
    data class EditHost(val operation: SearchOperation, val value: String) : SearchSettingsIntent
    data class SaveHost(val operation: SearchOperation) : SearchSettingsIntent
    data class SaveKey(val operation: SearchOperation, val key: Secret?) : SearchSettingsIntent
    data class PreferNative(val isEnabled: Boolean) : SearchSettingsIntent
    data class Check(val operation: SearchOperation) : SearchSettingsIntent
}

internal sealed interface SearchSettingsAction : MVIAction

/** FlowMVI owner for profile search settings and explicit connection checks. */
internal class SearchSettingsModel(
    private val configuration: SearchConfiguration,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("SearchSettings")
    val store = factory.create<SearchSettingsState, SearchSettingsIntent, SearchSettingsAction>(
        "SearchSettings",
        SearchSettingsState(),
        onError = { copy(failure = SearchFailure.Unavailable) },
    ) {
        reduce { intent ->
            when (intent) {
                SearchSettingsIntent.Load -> refresh()

                is SearchSettingsIntent.EditHost -> updateState {
                    if (intent.operation == SearchOperation.Search) {
                        copy(searchHost = intent.value)
                    } else {
                        copy(contentsHost = intent.value)
                    }
                }

                is SearchSettingsIntent.SaveHost -> perform {
                    withState {
                        configuration.setHost(
                            intent.operation,
                            if (intent.operation == SearchOperation.Search) searchHost else contentsHost,
                        )
                    }
                    refresh()
                }

                is SearchSettingsIntent.SaveKey -> try {
                    perform {
                        configuration.setKey(intent.operation, intent.key)
                        refresh()
                    }
                } finally {
                    intent.key?.close()
                }

                is SearchSettingsIntent.PreferNative -> perform {
                    configuration.setPreferNative(intent.isEnabled)
                    refresh()
                }

                is SearchSettingsIntent.Check -> {
                    updateState {
                        withCheck(
                            intent.operation,
                            CheckPhase.Checking,
                        ).withCheckFailure(intent.operation, null)
                    }
                    try {
                        configuration.check(intent.operation)
                        updateState { withCheck(intent.operation, CheckPhase.Success) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SearchException) {
                        log.w(e) { "Connection check failed: ${e.failure}" }
                        updateState {
                            withCheck(
                                intent.operation,
                                CheckPhase.Failure,
                            ).withCheckFailure(intent.operation, e.failure)
                        }
                    } catch (e: Exception) {
                        log.w(e) { "Connection check failed" }
                        updateState {
                            withCheck(
                                intent.operation,
                                CheckPhase.Failure,
                            ).withCheckFailure(intent.operation, SearchFailure.Unavailable)
                        }
                    }
                }
            }
        }
    }

    init {
        store.start(scope)
        scope.launch { store.intent(SearchSettingsIntent.Load) }
    }

    @Suppress("SuspendFunWithCoroutineScopeReceiver") // FlowMVI pipeline operations are suspend receivers.
    private suspend fun SearchPipeline.refresh() {
        val settings = configuration.read()
        updateState {
            copy(
                settings = settings,
                searchHost = settings.search.host,
                contentsHost = settings.contents.host,
                failure = null,
            )
        }
    }

    @Suppress("SuspendFunWithCoroutineScopeReceiver") // FlowMVI pipeline operations are suspend receivers.
    private suspend fun SearchPipeline.perform(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchException) {
            log.w(e) { "Settings update failed: ${e.failure}" }
            updateState { copy(failure = e.failure) }
        } catch (e: Exception) {
            log.w(e) { "Settings update failed" }
            updateState { copy(failure = SearchFailure.Unavailable) }
        }
    }
}

private typealias SearchPipeline =
    pro.respawn.flowmvi.api.PipelineContext<SearchSettingsState, SearchSettingsIntent, SearchSettingsAction>

private fun SearchSettingsState.withCheck(operation: SearchOperation, phase: CheckPhase): SearchSettingsState =
    if (operation == SearchOperation.Search) copy(searchCheck = phase) else copy(contentsCheck = phase)

private fun SearchSettingsState.withCheckFailure(
    operation: SearchOperation,
    failure: SearchFailure?,
): SearchSettingsState = if (operation == SearchOperation.Search) {
    copy(
        searchCheckFailure = failure,
    )
} else {
    copy(contentsCheckFailure = failure)
}
