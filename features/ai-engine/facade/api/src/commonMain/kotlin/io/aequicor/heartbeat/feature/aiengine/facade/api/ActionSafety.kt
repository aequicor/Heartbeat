package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Host-owned analysis of an exact operation. Tool names, action labels, model arguments and third-party
 * read-only annotations cannot establish [Known]. Only an authoritative analyzer that knows the actual
 * executor and has accounted for every effect and resource may produce it. An incomplete parse is [Unknown].
 */
public sealed interface ActionAnalysis {
    /** Complete structural evidence, still subject to policy, authority and execution-time revalidation. */
    public data class Known(val evidence: ActionEvidence) : ActionAnalysis {
        override fun toString(): String = "ActionAnalysis.Known"
    }

    /** No automatic permission can be inferred; [reason] is presentation data and never logged. */
    public data class Unknown(val reason: String) : ActionAnalysis {
        override fun toString(): String = "ActionAnalysis.Unknown"
    }

    /** An established hard restriction that neither model review nor a user approval may override. */
    public data class Denied(val reason: String) : ActionAnalysis {
        override fun toString(): String = "ActionAnalysis.Denied"
    }
}

/**
 * Complete, normalized facts produced by trusted code, never decoded directly from a tool call.
 * [operation] describes the executor's actual semantics, rather than accepting its caller's safety claims.
 * [resources] accounts for all affected targets and [effects] for all consequences, including nested execution,
 * redirects and network disclosure. [binding] identifies the exact arguments, executor and resource snapshot;
 * the gate must revalidate it before dispatch. An answer from a model is not an execution capability.
 */
public data class ActionEvidence(
    val operation: String,
    val resources: List<ActionResource>,
    val effects: Set<ActionEffect>,
    val binding: String,
) {
    override fun toString(): String = "ActionEvidence(resources=${resources.size}, effects=$effects)"
}

/**
 * One actual target resolved by the host. [identity] is a normalized path, destination or owner-qualified id.
 * [isWithinVerifiedRoot] may be true only after checking the actual filesystem target against the permitted
 * root, including symlinks and provider semantics, with the executor preserving that boundary until use.
 * It is never inferred from a lexical prefix, working-directory claim or third-party tool annotation.
 */
public data class ActionResource(
    val kind: ActionResourceKind,
    val identity: String,
    val isWithinVerifiedRoot: Boolean = false,
) {
    override fun toString(): String = "ActionResource(kind=$kind, withinRoot=$isWithinVerifiedRoot)"
}

/** Resource families understood by the shared policy, independent of tool names. */
public enum class ActionResourceKind { FileSystem, Network, Process, External }

/** Every known consequence of an operation; an unaccounted consequence requires [ActionAnalysis.Unknown]. */
public enum class ActionEffect { Read, Create, Modify, Delete, Execute, Network, ExternalChange }

/** A policy result for the captured operation; only the gate may bind it to one actual execution. */
public enum class ActionSafetyDecision { Allow, Ask, Deny }
