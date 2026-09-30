package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject

/** Profile services used by a runtime and the native sessions it owns. */
@Inject
internal data class PiRuntimeEnvironment(val sessions: PiSessionEnvironment, val nativeWeb: PiNativeWeb)
