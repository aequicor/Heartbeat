package io.aequicor.heartbeat.feature.attachments.impl.di

import com.arkivanov.decompose.ComponentContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.navigation.Navigator
import io.aequicor.heartbeat.core.navigation.ProfileRouteBinding
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.attachments.api.AttachmentPreviewRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEffect
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsEnabled
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsIntent
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsMachineSpec
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsOutput
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsPickRoute
import io.aequicor.heartbeat.feature.attachments.api.AttachmentsState
import io.aequicor.heartbeat.feature.attachments.impl.presentation.component.AttachmentComponent
import io.aequicor.heartbeat.feature.attachments.impl.ui.AttachmentUiComponent
import kotlinx.coroutines.launch

/** One profile-owned machine, independent of native UI screen lifetimes. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object AttachmentBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: EffectHandler<AttachmentsEffect, AttachmentsIntent>,
    ): Machine<AttachmentsState, AttachmentsIntent, AttachmentsOutput> =
        launcher.launch(AttachmentsMachineSpec, scope, effects)
}

@ContributesIntoSet(ProfileScope::class)
@Inject
internal class AttachmentStartup(
    private val machine: Lazy<Machine<AttachmentsState, AttachmentsIntent, AttachmentsOutput>>,
    @ForScope(ProfileScope::class) private val scope: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        val attachments = machine.value
        scope.coroutineScope.launch { attachments.send(AttachmentsIntent.Public.Start) }
    }
}

/** Registers the feature flag while the durable catalog remains available unconditionally. */
@ContributesTo(AppScope::class)
@BindingContainer
public object AttachmentToggleBindings {
    /** Registers the exact toggle type in the application controls. */
    @Provides
    @IntoSet
    public fun attachments(): FeatureToggle<*> = AttachmentsEnabled
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class AttachmentPickEntry(private val factory: AttachmentComponent.Factory) :
    RouteEntry<AttachmentsPickRoute>(AttachmentsPickRoute::class, AttachmentsPickRoute.serializer()) {
    override fun create(route: AttachmentsPickRoute, context: ComponentContext, navigator: Navigator) =
        AttachmentUiComponent(factory.create(context, navigator, route, null))
}

@ContributesIntoSet(ProfileScope::class, binding = binding<ProfileRouteBinding>())
@Inject
internal class AttachmentPreviewEntry(private val factory: AttachmentComponent.Factory) :
    RouteEntry<AttachmentPreviewRoute>(AttachmentPreviewRoute::class, AttachmentPreviewRoute.serializer()) {
    override fun create(route: AttachmentPreviewRoute, context: ComponentContext, navigator: Navigator) =
        AttachmentUiComponent(factory.create(context, navigator, null, route))
}
