package io.aequicor.heartbeat.core.profilefacade

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.ScopeHandle

/**
 * Hooks into the creation of a profile graph for hosts whose own graphs live next to it (desktop bundles).
 * Contributed with `@ContributesIntoSet(AppScope::class)`; [attach] runs once per profile, right after the
 * graph is created and before [ProfileStartup]s are read, so what it adds is part of the profile when it starts.
 * A failing attachment is logged and does not keep the profile closed.
 */
public fun interface ProfileGraphAttachment {
    /** Attaches to the [graph] of profile [id]; register cleanup on [scope], it closes with the profile. */
    public fun attach(id: ProfileId, graph: ProfileGraph, scope: ScopeHandle)
}

/** Declares the (possibly empty) set of [ProfileGraphAttachment]s. */
@ContributesTo(AppScope::class)
@BindingContainer
public interface ProfileGraphAttachmentBindings {
    /** Attachments run for every new profile; empty on platforms without bundles. */
    @Multibinds(allowEmpty = true)
    public fun profileGraphAttachments(): Set<ProfileGraphAttachment>
}
