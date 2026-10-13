package io.aequicor.heartbeat.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The browser page is single-threaded and has no blocking IO: `io` work shares the default dispatcher. */
@Suppress("InjectDispatcher") // the platform source of DefaultDispatcherProvider.io
internal actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.Default
