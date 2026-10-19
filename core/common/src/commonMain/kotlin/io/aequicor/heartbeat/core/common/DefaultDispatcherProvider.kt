package io.aequicor.heartbeat.core.common

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The only place that references `Dispatchers.*` directly — everything else injects [DispatcherProvider]. */
@Suppress("InjectDispatcher") // this class *is* the injected dispatcher source
@ContributesBinding(AppScope::class)
@ContributesBinding(HostBootstrapScope::class)
@Inject
internal class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = platformIoDispatcher()
}

/** `Dispatchers.IO` where the platform has one; the browser has no blocking IO and no `Dispatchers.IO`. */
internal expect fun platformIoDispatcher(): CoroutineDispatcher
