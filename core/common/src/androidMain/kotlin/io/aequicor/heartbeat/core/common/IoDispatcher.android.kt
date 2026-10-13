package io.aequicor.heartbeat.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Suppress("InjectDispatcher") // the platform source of DefaultDispatcherProvider.io
internal actual fun platformIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
