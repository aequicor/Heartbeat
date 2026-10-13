package io.aequicor.heartbeat.feature.harness.impl.data.serviceui

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.HarnessTools
import io.aequicor.heartbeat.feature.harness.api.script.ScriptUiCall
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveSnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRuntime
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessUiDispatch
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessUiOutcome
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDataStorage
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwnerInfo
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwners
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Harnesses as owners of agent-built interfaces: a harness exists while it is in the library, is active where it is
 * connected to the chat (or chosen for a new one) and offers the UI methods its running scripts publish. The library
 * snapshot is complete only while the feature is on and the library is ready, not suspended and not reloading, so
 * surfaces are deleted only with their harness. Scripts run only where the script host is available (desktop).
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessServiceUiProvider(
    machines: MachineRegistry,
    toggles: FeatureToggles,
    private val host: HarnessScriptHost,
    private val runtime: Lazy<HarnessRuntime>,
    private val dispatch: Lazy<HarnessUiDispatch>,
    private val data: HarnessDataStorage,
) : ServiceUiProvider {
    private val log = Log.tag("HarnessServiceUi")

    override val kind: String = HarnessTools.GROUP

    @OptIn(ExperimentalCoroutinesApi::class)
    private val library: Flow<HarnessActiveSnapshot> = combine(
        toggles.observe(HarnessEnabled),
        machines.observe(HarnessMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
    ) { isEnabled, state -> HarnessActiveSnapshot(isEnabled, state) }

    override val owners: Flow<UiOwners> = library.map { it.owners() }.distinctUntilChanged()

    override fun activeOwners(context: UiCallContext): Flow<Set<String>> =
        library.map { it.active(context) }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun methods(owner: String): Flow<UiMethods> {
        if (!host.isAvailable) return flowOf(UiMethods(isAvailable = false))
        val harness = HarnessId(owner)
        return runtime.value.publishedInstances.mapLatest { instances ->
            if (instances.none { it.request.harness.id == harness }) {
                UiMethods(isAvailable = false)
            } else {
                UiMethods(true, dispatch.value.methods(harness).map { UiMethodInfo(it.name.value, it.description) })
            }
        }.distinctUntilChanged()
    }

    override fun dataRevision(owner: String): Flow<Long> = data.revision(HarnessId(owner))

    override suspend fun call(owner: String, call: UiMethodCall): UiMethodResult {
        val isActive = owner in library.first().active(call.context)
        val result = when {
            !host.isAvailable -> UiMethodResult.Failure(UiMethodFailure.Unavailable)

            !isActive -> UiMethodResult.Failure(UiMethodFailure.Inactive)

            else -> dispatch.value.call(
                HarnessId(owner),
                call.method,
                ScriptUiCall(call.arguments, call.context.session, call.context.workspace),
            ).result()
        }
        log.v { "UI method ${call.method} of a harness: ${result::class.simpleName ?: "UiMethodResult"}" }
        return result
    }
}

private fun HarnessActiveSnapshot.owners(): UiOwners {
    val ready = library as? HarnessState.Ready
    val isComplete = isEnabled && ready != null && !ready.isSuspended && !ready.isReloading
    val owners = ready?.harnesses.orEmpty().map { entry ->
        val harness = entry.harness
        UiOwnerInfo(harness.id.value, harness.name.value, harness.title, isEnabled && harness.isEnabled)
    }
    return UiOwners(isComplete, owners)
}

/** Connected harnesses of the chat, or the ones chosen for a chat that is not created yet. */
private fun HarnessActiveSnapshot.active(context: UiCallContext): Set<String> {
    if (library is HarnessState.Failed) return emptySet()
    val session = context.session
    val chosen = if (session != null) {
        select(session)
    } else {
        opening(
            context.preselected[HarnessTools.GROUP].orEmpty().filter { it.isNotBlank() }.mapTo(
                mutableSetOf(),
                ::HarnessId,
            ),
        )
    }
    return chosen.orEmpty().mapTo(mutableSetOf()) { it.id.value }
}

private fun HarnessUiOutcome.result(): UiMethodResult = when (this) {
    is HarnessUiOutcome.Value -> UiMethodResult.Success(value)
    is HarnessUiOutcome.UserError -> UiMethodResult.UserError(message)
    HarnessUiOutcome.NotFound -> UiMethodResult.Failure(UiMethodFailure.NotFound)
    HarnessUiOutcome.Inactive -> UiMethodResult.Failure(UiMethodFailure.Inactive)
    HarnessUiOutcome.Unavailable -> UiMethodResult.Failure(UiMethodFailure.Unavailable)
    HarnessUiOutcome.TimedOut -> UiMethodResult.Failure(UiMethodFailure.TimedOut)
    HarnessUiOutcome.Failed -> UiMethodResult.Failure(UiMethodFailure.Failed)
    HarnessUiOutcome.TooLarge -> UiMethodResult.Failure(UiMethodFailure.TooLarge)
}
