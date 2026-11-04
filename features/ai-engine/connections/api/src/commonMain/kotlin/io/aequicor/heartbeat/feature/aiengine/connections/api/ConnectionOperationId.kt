package io.aequicor.heartbeat.feature.aiengine.connections.api

/** Machine-local sequence of an accepted write; effect scopes prevent reuse across machine instances. */
public data class ConnectionOperationId(val value: Long) {
    init {
        require(value > 0) { "Connection operation id must be positive" }
    }
}

/** Internal phases of the same bounded save operation, displayed as one pending action. */
public enum class ModelSavePhase { Saving, Checking }
