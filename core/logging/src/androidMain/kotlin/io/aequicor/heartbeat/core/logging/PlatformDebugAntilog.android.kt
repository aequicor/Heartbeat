package io.aequicor.heartbeat.core.logging

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog

internal actual fun platformDebugAntilog(): Antilog = DebugAntilog()
