package io.aequicor.heartbeat.feature.browser.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.browser.api.BrowserEffect
import io.aequicor.heartbeat.feature.browser.api.BrowserIntent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Keeps live policy and native feedback in the machine while its running state remains active. */
internal class BrowserEffects(private val availability: BrowserAvailability, private val session: BrowserSession) :
    EffectHandler<BrowserEffect, BrowserIntent> {
    override suspend fun handle(effect: BrowserEffect, machine: EffectScope<BrowserIntent>) {
        when (effect) {
            BrowserEffect.Observe -> coroutineScope {
                launch {
                    session.pages.collect { machine.send(BrowserIntent.Internal.PageChanged(it)) }
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
}
