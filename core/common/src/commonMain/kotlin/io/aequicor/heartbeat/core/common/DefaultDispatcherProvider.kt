// `Dispatchers.IO` is a member on JVM but an extension on native: the import is required for iOS,
// while detekt's JVM type resolution reports it as unused.
@file:Suppress("UnusedImport")

package io.aequicor.heartbeat.core.common

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** The only place that references `Dispatchers.*` directly — everything else injects [DispatcherProvider]. */
@Suppress("InjectDispatcher") // this class *is* the injected dispatcher source
@ContributesBinding(AppScope::class)
@Inject
internal class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
}
