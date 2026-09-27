package io.aequicor.heartbeat.core.statemachine

import kotlin.reflect.KClass

/** Class name for logs, diagrams and errors; `?` for anonymous classes. */
internal val KClass<*>.label: String get() = simpleName ?: "?"
