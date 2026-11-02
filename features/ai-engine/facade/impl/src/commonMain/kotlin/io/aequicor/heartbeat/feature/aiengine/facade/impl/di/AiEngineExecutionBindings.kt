package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.SessionExecutionBindingSpec
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.StoredSessionExecutionBindings
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionAccess
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionAdmissions
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.SessionExecutionBindings

/** Immutable native authority and profile admission barriers; canonical lifecycle remains in each owning host. */
@ContributesTo(ProfileScope::class)
@BindingContainer
object AiEngineExecutionBindings {
    /** Strict permanent metadata, independent of native discovery. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun bindings(
        @ForScope(ProfileScope::class) stores: DataStores,
    ): SessionExecutionBindings = StoredSessionExecutionBindings(stores.keyValue(SessionExecutionBindingSpec))

    /** Lazy host lookup avoids a facade-to-host construction cycle; no provider means no admission. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun admissions(
        authorities: Lazy<Set<SessionExecutionAuthority>> = lazyOf(emptySet()),
    ): SessionExecutionAdmissions = SessionExecutionAdmissions(authorities)

    /** Lifecycle owners use the same gate as native opening and submission. */
    @Provides
    fun control(access: SessionExecutionAccess): SessionExecutionControl = access

    /** Opening-time immutable binding restoration. */
    @Provides
    @SingleIn(ProfileScope::class)
    fun access(bindings: SessionExecutionBindings, admissions: SessionExecutionAdmissions): SessionExecutionAccess =
        SessionExecutionAccess(bindings, admissions)
}
