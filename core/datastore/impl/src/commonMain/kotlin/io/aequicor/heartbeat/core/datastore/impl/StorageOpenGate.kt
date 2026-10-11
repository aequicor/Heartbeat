package io.aequicor.heartbeat.core.datastore.impl

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Closes admission synchronously, then invokes [onDrained] once every previously admitted synchronous factory
 * has returned. A cache's not-yet-initialized Lazy must stay registered until its construction is accounted for.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class StorageOpenGate(private val onDrained: () -> Unit) {
    private val state = AtomicReference(OpenState())

    fun <T> open(block: () -> T): T {
        while (true) {
            val current = state.load()
            check(!current.isClosing) { "Storage owner is closing" }
            if (state.compareAndSet(current, current.copy(active = current.active + 1))) break
        }
        try {
            return block()
        } finally {
            leave()
        }
    }

    fun close() {
        while (true) {
            val current = state.load()
            if (current.isClosing) return
            if (state.compareAndSet(current, current.copy(isClosing = true))) {
                if (current.active == 0) onDrained()
                return
            }
        }
    }

    private fun leave() {
        while (true) {
            val current = state.load()
            val next = current.copy(active = current.active - 1)
            if (state.compareAndSet(current, next)) {
                if (next.isClosing && next.active == 0) onDrained()
                return
            }
        }
    }
}

private data class OpenState(val active: Int = 0, val isClosing: Boolean = false)
