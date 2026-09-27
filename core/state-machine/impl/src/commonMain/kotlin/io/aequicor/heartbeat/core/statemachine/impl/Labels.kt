package io.aequicor.heartbeat.core.statemachine.impl

/** Class name only: states, intents and effects may hold user content or secrets. */
internal fun Any.label(): String = this::class.simpleName ?: "?"
