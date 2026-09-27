package io.aequicor.heartbeat.feature.aiengine.facade.api

import kotlinx.serialization.Serializable

/** Engine registration identity. Never contains credentials or account names. */
@Serializable
public data class EngineId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty EngineId" }
    }
}

/** Profile-scoped binding identity. Never contains credentials or account names. */
@Serializable
public data class EngineBindingId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty EngineBindingId" }
    }
}

/**
 * Native model identity, resolved only together with a binding and engine. Never contains credentials
 * or account names.
 */
@Serializable
public data class ModelId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty ModelId" }
    }
}

/** Profile-scoped reference to a host and native session store. Never contains credentials or account names. */
@Serializable
public data class SessionSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty SessionSourceId" }
    }
}

/** Opaque workspace reference; native paths are resolved by adapters. Never contains credentials or account names. */
@Serializable
public data class WorkspaceRef(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty WorkspaceRef" }
    }
}

/** Stable capability identifier. Never contains credentials or account names. */
@Serializable
public data class EngineFeatureId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty EngineFeatureId" }
    }
}

/**
 * Client-generated correlation identity; does not imply native idempotency. Never contains credentials
 * or account names.
 */
@Serializable
public data class RequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty RequestId" }
    }
}

/** Stable turn identity within a session. Never contains credentials or account names. */
@Serializable
public data class TurnId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty TurnId" }
    }
}

/** Stable history item identity within a session. Never contains credentials or account names. */
@Serializable
public data class ItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty ItemId" }
    }
}

/** Correlation identity joining tool calls and results. Never contains credentials or account names. */
@Serializable
public data class ToolCallId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty ToolCallId" }
    }
}

/** Identity of an outstanding permission request. Never contains credentials or account names. */
@Serializable
public data class PermissionRequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty PermissionRequestId" }
    }
}

/** Identity of one engine-provided permission decision. Never contains credentials or account names. */
@Serializable
public data class PermissionOptionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty PermissionOptionId" }
    }
}

/** Safe random diagnostic correlation identifier. Never contains credentials or account names. */
@Serializable
public data class DiagnosticId(val value: String) {
    init {
        require(value.isNotBlank()) { "Empty DiagnosticId" }
    }
}
