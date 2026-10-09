package io.aequicor.heartbeat.feature.harness.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.harness.impl.data.delivery.HarnessContentHook
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessDeliveryStorage
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessActiveAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessContextDelivery
import io.aequicor.heartbeat.feature.harness.impl.domain.content.HarnessScriptInstructionAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import okio.ByteString.Companion.encodeUtf8

/** Delivery queue belongs to the profile; constructing the hook does not open a facade or a library. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object HarnessContentBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun delivery(
        storage: HarnessDeliveryStorage,
        @ForScope(ProfileScope::class) profile: ScopeHandle,
    ): HarnessContextDelivery = HarnessContextDelivery(storage, profile.coroutineScope) {
        it.encodeUtf8().sha256().hex()
    }

    @Provides @IntoSet
    internal fun hook(
        gate: HarnessEventGate,
        facade: Lazy<EngineFacade>,
        access: Lazy<HarnessActiveAccess>,
        delivery: Lazy<HarnessContextDelivery>,
        scripts: Lazy<HarnessScriptInstructionAccess>,
    ): SessionHook = HarnessContentHook(
        { gate.isEnabled },
        { session ->
            facade.value.engines.state.value.find { it.descriptor.id == session.engine }
                ?.descriptor?.areInstructionsRefreshedPerTurn == true
        },
        access,
        delivery,
        scripts,
    )
}
