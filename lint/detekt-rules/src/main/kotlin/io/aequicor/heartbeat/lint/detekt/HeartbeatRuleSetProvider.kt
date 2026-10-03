package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Heartbeat rule set `heartbeat`: logging policy and error handling —
 * every action, state change, network/storage access and configuration change is logged,
 * no error is ignored (except `CancellationException`, which is rethrown).
 */
class HeartbeatRuleSetProvider : RuleSetProvider {

    override val ruleSetId: RuleSetId = RuleSetId("heartbeat")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::RawLoggingCall,
            ::HighFrequencyLog,
            ::SwallowedError,
            ::CancellationSwallowed,
            ::GenericExceptionCaught,
            ::UnhandledResultFailure,
            ::StateChangeNotLogged,
            ::DataAccessNotLogged,
            ::LoggingInfrastructureBypass,
            ::SensitiveDataLogged,
            ::FeatureLayerPlacement,
            ::FeatureLayerDependency,
        ),
    )
}
