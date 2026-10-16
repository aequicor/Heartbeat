package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Enables managed Git worktrees and their coordinated builds on Desktop. */
public val WorktreeModeEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "worktree_mode.enabled",
    "Изолированные Git worktree, общий кеш сборки и завершение задач",
    default = false,
)

/**
 * New isolated tasks become source-only sandboxes: their builds never run in the task checkout but in a shared pool
 * of warm build slots of the repository, from an immutable snapshot of the task's files. Existing tasks keep the
 * isolation they were created with.
 */
public val WorktreeSandboxEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "worktree_mode.sandbox",
    "Песочницы: задачи без сборочных выходов, сборки в общих слотах проекта",
    default = false,
)

/**
 * Weighs every coordinated build against the machine's CPU and memory budget (or the user limit in the "Builds"
 * settings section) and shares it fairly between projects. Off keeps the previous admission: builds wait only for
 * their exclusive resources.
 */
public val WorktreeBuildQueueEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "worktree_mode.build_queue",
    "Очередь сборок по ёмкости машины: веса типов сборок, лимит и справедливость между проектами",
    default = false,
)

/** Lifecycle of one chat's checkout; native turn completion alone never completes a task. */
@Serializable
public enum class WorktreePhase {
    Preparing,
    Idle,
    Working,
    CompletionSignaled,
    AwaitingDecision,
    ActionWorking,
    Retained,
    RecoveryRequired,
    Failed,
}

/** Native follow-up purpose, assigned by Heartbeat rather than by tool arguments. */
@Serializable
public enum class WorktreeRunKind { Coding, CreatePr, Merge }

/** User choice after an explicitly completed coding task. */
@Serializable
public enum class WorktreeAction { CreatePr, Merge, Refine, Leave }

/** Trusted host correlation captured before queueing a hosted build/configuration effect. */
public data class WorktreeRunIdentity(val request: RequestId, val session: SessionRef, val turn: TurnId)

/** Correlated native execution; a task-completion signal is valid only for this request and turn. */
@Serializable
public data class WorktreeRun(
    val request: RequestId,
    val kind: WorktreeRunKind = WorktreeRunKind.Coding,
    val session: SessionRef? = null,
    val turn: TurnId? = null,
    val outcome: TurnOutcome? = null,
    @SerialName("completionSignaled") val isCompletionSignaled: Boolean = false,
    val summary: String? = null,
    val pullRequestUrl: String? = null,
    @SerialName("prepared") val isPrepared: Boolean = false,
)

/** Durable delivery request; an ambiguous delivery is not automatically repeated after a restart. */
@Serializable
public data class WorktreeActionRequest(val operation: String, val kind: WorktreeRunKind, val prompt: String)

/** Correlation retained after prompt handoff until the exact native submission is recorded. */
@Serializable
public data class WorktreeExpectedAction(val operation: String, val kind: WorktreeRunKind)

/**
 * Declared load class of a build command. The application build queue weighs concurrent builds by kind against the
 * machine's CPU and memory budget; an undeclared command is treated as [Compile].
 */
@Serializable
public enum class WorktreeBuildKind {
    /** Static analysis, formatting and other light single-pass checks. */
    Check,

    /** Compilation of one or more modules. */
    Compile,

    /** Compilation followed by test execution. */
    Test,

    /** Packaging of application artifacts. */
    Assemble,

    /** Native toolchains (Kotlin/Native, Xcode, linkers) with the highest CPU and memory demand. */
    Native,
}

/** An argv command runs in a directory relative to the isolated checkout, without an implicit shell. */
@Serializable
public data class WorktreeBuildCommand(
    val id: String,
    val executable: String,
    val arguments: List<String> = emptyList(),
    val directory: String = ".",
    val environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 900_000L,
    val kind: WorktreeBuildKind = WorktreeBuildKind.Compile,
)

/**
 * Cache locations are relative to the original project or absolute; commands refer to `{cache:id}`. A cache is an
 * exclusive build resource unless [isConcurrent] declares that its tool coordinates concurrent processes itself
 * (for example `GRADLE_USER_HOME`); only then may builds of different slots share it at the same time.
 */
@Serializable
public data class WorktreeBuildCache(
    val id: String,
    val directory: String,
    @SerialName("concurrent") val isConcurrent: Boolean = false,
)

/** Agent-discovered build configuration; approval belongs to this exact revision of the declaration. */
@Serializable
public data class WorktreeBuildPlan(
    val system: String,
    val commands: List<WorktreeBuildCommand>,
    val caches: List<WorktreeBuildCache> = emptyList(),
    val exclusiveResources: List<String> = emptyList(),
)

/** Build state persisted before spawning the worker; output is bounded and must not contain credentials. */
@Serializable
public enum class WorktreeBuildPhase { Queued, WaitingForResource, Running, Completed, Cancelled, Failed, Unknown }

/** One build operation; a crash is Unknown until worker reconciliation establishes its result. */
@Serializable
public data class WorktreeBuildOperation(
    val id: String,
    val command: String,
    val phase: WorktreeBuildPhase = WorktreeBuildPhase.Queued,
    val exitCode: Int? = null,
    val output: String = "",
    val failure: String? = null,
    val queuePosition: Int? = null,
    val configurationRevision: Long = 0,
)

/**
 * Path-free, durable task projection; the execution workspace is an opaque adapter reference. A sandbox task
 * ([isSandbox]) keeps only sources in its checkout: hosted builds run in shared build slots of the repository.
 */
@Serializable
public data class WorktreeTask(
    val chatId: String,
    val project: WorkspaceRef,
    val executionWorkspace: WorkspaceRef? = null,
    val sourceBranch: String? = null,
    val branch: String? = null,
    val baseCommit: String? = null,
    val phase: WorktreePhase = WorktreePhase.Preparing,
    val run: WorktreeRun? = null,
    val actionRequest: WorktreeActionRequest? = null,
    val buildPlan: WorktreeBuildPlan? = null,
    @SerialName("buildApproved") val isBuildApproved: Boolean = false,
    val builds: Map<String, WorktreeBuildOperation> = emptyMap(),
    val failure: String? = null,
    val revision: Long = 0,
    @SerialName("completionDismissed") val isCompletionDismissed: Boolean = false,
    val isIsolated: Boolean = true,
    val buildConfigurationRevision: Long = 0,
    val verifiedPullRequestUrl: String? = null,
    val expectedAction: WorktreeExpectedAction? = null,
    @SerialName("sandbox") val isSandbox: Boolean = false,
)
