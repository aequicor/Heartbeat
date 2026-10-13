package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiLimits
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodCall
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.spi.ServiceUiProvider
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Calls of owner methods for user actions, through the provider of the owner's kind. A call that outlives the
 * provider's own budget is reported as timed out; results over [RESULT_BYTES] are refused. Logs who was called,
 * the outcome and the duration, never arguments or values.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class ProviderUiMethodCalls(private val providers: Set<ServiceUiProvider>) : UiMethodCalls {
    private val log = Log.tag("ServiceUiCalls")

    override fun activeOwners(context: UiCallContext): Flow<Set<UiOwner>> {
        if (providers.isEmpty()) return flowOf(emptySet())
        val active = providers.map { provider ->
            provider.activeOwners(context)
                .map<Set<String>, Set<UiOwner>> { ids -> ids.mapNotNullTo(mutableSetOf()) { owner(provider.kind, it) } }
                .catch { e ->
                    log.w(e.redactedFailure()) { "Active owners of ${provider.kind} stopped" }
                    emit(emptySet())
                }
        }
        return combine(active) { sets -> sets.flatMapTo(mutableSetOf()) { it } }
    }

    private fun owner(kind: String, id: String): UiOwner? = try {
        UiOwner(kind, id)
    } catch (e: IllegalArgumentException) {
        log.w(e) { "Provider $kind reported an invalid owner id" }
        null
    }

    override fun dataRevision(owner: UiOwner): Flow<Long> {
        val provider = providers.firstOrNull { it.kind == owner.kind } ?: return flowOf(0L)
        return provider.dataRevision(owner.id).catch { e ->
            log.w(e.redactedFailure()) { "Data revisions of ${owner.kind} owner stopped" }
            emit(0L)
        }
    }

    override fun methods(owner: UiOwner): Flow<UiMethods> {
        val provider = providers.firstOrNull { it.kind == owner.kind } ?: return flowOf(UiMethods(isAvailable = false))
        return provider.methods(owner.id).catch { e ->
            log.w(e.redactedFailure()) { "Methods of ${owner.kind} owner stopped" }
            emit(UiMethods(isAvailable = false))
        }
    }

    override suspend fun call(
        owner: UiOwner,
        method: String,
        arguments: JsonObject,
        context: UiCallContext,
    ): UiMethodResult = perform(owner, method, arguments, context, isPage = false)

    @HighFrequency
    override suspend fun callFromPage(
        owner: UiOwner,
        method: String,
        arguments: JsonObject,
        context: UiCallContext,
    ): UiMethodResult = perform(owner, method, arguments, context, isPage = true)

    private suspend fun perform(
        owner: UiOwner,
        method: String,
        arguments: JsonObject,
        context: UiCallContext,
        isPage: Boolean,
    ): UiMethodResult {
        val started = TimeSource.Monotonic.markNow()
        val provider = providers.firstOrNull { it.kind == owner.kind }
        val result = when {
            provider == null -> UiMethodResult.Failure(UiMethodFailure.Unavailable)

            arguments.toString().encodeToByteArray().size > ServiceUiLimits.ARGS_BYTES ->
                UiMethodResult.Failure(UiMethodFailure.TooLarge)

            else -> invoke(provider, owner, UiMethodCall(method, arguments, context))
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds
        if (isPage) {
            log.v { "Page call $method of ${owner.kind} ${owner.id}: ${result.outcome()} in $elapsed ms" }
        } else {
            log.i { "Call $method of ${owner.kind} ${owner.id}: ${result.outcome()} in $elapsed ms" }
        }
        return result
    }

    private suspend fun invoke(provider: ServiceUiProvider, owner: UiOwner, call: UiMethodCall): UiMethodResult {
        val result = try {
            withTimeoutOrNull(CALL_TIMEOUT) { provider.call(owner.id, call) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Script failures may quote the arguments or results they worked on; only the type is logged.
            log.w(e.redactedFailure()) { "Provider ${provider.kind} failed a call" }
            UiMethodResult.Failure(UiMethodFailure.Failed)
        }
        return when {
            result == null -> UiMethodResult.Failure(UiMethodFailure.TimedOut)

            result is UiMethodResult.Success && result.value.toString().encodeToByteArray().size > RESULT_BYTES ->
                UiMethodResult.Failure(UiMethodFailure.TooLarge)

            else -> result
        }
    }
}

/** Results larger than this are refused before they reach a surface. */
internal const val RESULT_BYTES: Int = 256 * 1024

/** The provider's own budget is 60 seconds; this covers waiting for its worker as well. */
private val CALL_TIMEOUT = 65.seconds

private fun UiMethodResult.outcome(): String = when (this) {
    is UiMethodResult.Success -> "success"
    is UiMethodResult.UserError -> "user error"
    is UiMethodResult.Failure -> reason.name
}
