package io.aequicor.heartbeat.core.logging

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog

/** Napier writes to the browser console of the extension page (DevTools of the side panel). */
internal actual fun platformDebugAntilog(): Antilog = DebugAntilog()
