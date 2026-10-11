package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.harness.api.event.HarnessEvent
import kotlin.reflect.KClass

/** Events use bounded per-activation queues; handlers have a 30-second budget and must treat payload as data. */
public interface ScriptEvents {
    /** Subscribes to [type], including its subtypes. Session notifications are filtered by current activation. */
    public fun <E : HarnessEvent> on(type: KClass<E>, handler: suspend (E) -> Unit): ScriptRegistration
}

/** Typed registration convenient in script source without exposing reflection or runtime implementation objects. */
public inline fun <reified E : HarnessEvent> ScriptEvents.on(
    noinline handler: suspend (E) -> Unit,
): ScriptRegistration = on(E::class, handler)
