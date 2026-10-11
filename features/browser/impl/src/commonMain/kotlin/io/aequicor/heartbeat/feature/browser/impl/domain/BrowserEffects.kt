package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.browser.api.BrowserEffect
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import io.aequicor.heartbeat.feature.browser.api.BrowserResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps live policy and native feedback in the machine while its running state remains active. */
internal class BrowserEffects(private val availability: BrowserAvailability, private val session: BrowserSession) :
    EffectHandler<BrowserEffect, BrowserIntent> {
    override suspend fun handle(effect: BrowserEffect, machine: EffectScope<BrowserIntent>) {
        when (effect) {
            is BrowserEffect.Perform -> perform(effect, machine)

            is BrowserEffect.SelectTab -> session.selectTab(effect.id)

            is BrowserEffect.CancelOperation -> {
                session.cancelOperation(effect.id)
                machine.send(BrowserIntent.Internal.OperationCancelled(effect.id))
            }

            BrowserEffect.Observe ->
                coroutineScope {
                    launch {
                        session.tabs.collect { (tabs, selected) ->
                            machine.send(BrowserIntent.Internal.TabsChanged(tabs, selected))
                        }
                    }
                    launch {
                        session.pages.collect {
                            machine.send(BrowserIntent.Internal.PageChanged(it))
                        }
                    }
                    availability.observe().collect { enabled ->
                        session.setEnabled(enabled)
                        machine.send(BrowserIntent.Internal.AvailabilityChanged(enabled))
                    }
                }

            is BrowserEffect.Load -> session.execute(BrowserCommand.Load(effect.url))

            BrowserEffect.Back -> session.execute(BrowserCommand.Back)

            BrowserEffect.Forward -> session.execute(BrowserCommand.Forward)

            BrowserEffect.Reload -> session.execute(BrowserCommand.Reload)

            BrowserEffect.Stop -> session.execute(BrowserCommand.Stop)
        }
    }

    private suspend fun perform(effect: BrowserEffect.Perform, machine: EffectScope<BrowserIntent>) {
        var result: BrowserResult? = null
        try {
            result = session.perform(effect.request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.tag("BrowserEffects").w(IllegalStateException(error::class.simpleName)) {
                "Browser operation failed"
            }
            result = BrowserResult(effect.request.id, error = "Browser operation failed")
        } finally {
            withContext(NonCancellable) {
                machine.send(
                    BrowserIntent.Internal.Completed(
                        result
                            ?: BrowserResult(
                                effect.request.id,
                                error = "Browser operation cancelled",
                            ),
                    ),
                )
            }
        }
    }
}
