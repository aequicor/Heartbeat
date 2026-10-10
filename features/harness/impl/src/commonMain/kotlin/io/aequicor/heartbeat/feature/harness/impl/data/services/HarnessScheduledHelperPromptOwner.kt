package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.harness.impl.data.events.HarnessEventAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessRequestAncestry
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.harnessScriptFailure
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HARNESS_WAKE_OWNER
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperAttachments
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessHelperBindings
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import io.aequicor.heartbeat.feature.scheduler.api.spi.HelperPromptAttempt
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledHelperPromptOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest

/**
 * Ownership comes from the durable helper binding, never from the context or ActionId spelling. An owned helper
 * is attached before its first native prompt; explicit targets still obey activation and cap ordering. Ordinary
 * initiating requests relay restrictions without starting the library, even while the feature is disabled.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class HarnessScheduledHelperPromptOwner(
    private val bindings: Lazy<HarnessHelperBindings>,
    private val attachments: Lazy<HarnessHelperAttachments>,
    private val access: Lazy<HarnessSchedulerAccess>,
    private val ancestry: HarnessEventAncestry,
    private val storage: Lazy<HarnessRequestAncestry>,
) : ScheduledHelperPromptOwner {
    override val feature: String = HARNESS_WAKE_OWNER
    override val isObservingInitiators: Boolean = true
    private val ownership = HarnessOwnedContext()
    private val log = Log.tag("HarnessHelperAdmission")

    override fun admission(attempt: HelperPromptAttempt): Flow<Boolean> = flow {
        if (attempt.handoff?.ownerFeature == feature) {
            emitAll(owned(attempt))
        } else {
            persist(attempt)
            emit(true)
        }
    }.catch { error ->
        if (error is CancellationException || error !is Exception) throw error
        log.w(harnessScriptFailure(error)) { "Reject helper with unavailable harness admission" }
        emit(false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun owned(attempt: HelperPromptAttempt): Flow<Boolean> = flow {
        val owner = checkNotNull(ownership.decode(attempt.handoff?.ownerContext)) { "Missing helper owner" }
        val binding = checkNotNull(bindings.value.lookup(attempt.helper.id)) { "Missing helper binding" }
        check(
            binding.helper == attempt.helper.id && binding.owner == attempt.helper.owner &&
                binding.harness == owner.harness && (binding.session == null || binding.session == attempt.session),
        ) {
            "Helper binding does not match host identity"
        }
        emitAll(
            access.value.permits(owner.harness, null).transformLatest { publisher ->
                if (publisher == null) {
                    emit(false)
                } else {
                    val bound = bindings.value.bindSession(binding.helper, binding.owner, attempt.session)
                    persist(attempt)
                    if (!publisher.isCurrent() || !attachments.value.ensureAttached(bound)) {
                        emit(false)
                    } else {
                        emitAll(
                            access.value.permits(owner.harness, HarnessTarget(attempt.session, attempt.workspace))
                                .mapLatest { target ->
                                    val isAllowed = target != null && target.isCurrent()
                                    currentCoroutineContext().ensureActive()
                                    isAllowed
                                },
                        )
                    }
                }
            },
        )
    }

    private suspend fun persist(attempt: HelperPromptAttempt) {
        val origin = ancestry.helper(attempt)
        if (origin.isHookRestricted || origin.sendChain.isNotEmpty()) {
            storage.value.restrict(attempt.session, attempt.request, origin)
        }
        currentCoroutineContext().ensureActive()
    }
}
