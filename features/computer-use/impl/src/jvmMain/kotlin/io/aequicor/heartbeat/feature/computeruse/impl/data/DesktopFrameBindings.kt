package io.aequicor.heartbeat.feature.computeruse.impl.data

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameEncoder
import io.aequicor.heartbeat.feature.computeruse.impl.domain.FrameStore

/** Desktop codecs and frame storage; the capture, window and input hosts bind themselves. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object DesktopFrameBindings {
    /** JDK codecs: lossless PNG and lossy JPEG. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun encoder(): FrameEncoder = DesktopFrameEncoder()

    /** Frames live under the application storage root, one directory per capture session. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun store(root: StorageRoot, dispatchers: DispatcherProvider): FrameStore =
        DesktopFrameStore(root::path, dispatchers)
}
