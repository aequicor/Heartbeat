package io.aequicor.heartbeat.feature.scheduler.api

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle

/**
 * Session scheduler: the hosted sleep/signal tools, delivery of due wakes, the network source and session lifecycle
 * events. While off, agents get no tools and nothing is delivered; pending wakes are kept for later.
 */
public val SchedulerEnabled: FeatureToggle.Flag = FeatureToggle.Flag(
    "scheduler.enabled",
    "Планировщик: сон сессии и пробуждение по времени, таймеру, сети и событиям других сессий",
)

/**
 * Background actions of the scheduler: an agent starts a command (Desktop, in its project) or a helper agent and
 * wakes on its result. Requires [SchedulerEnabled].
 */
public val SchedulerActions: FeatureToggle.Flag = FeatureToggle.Flag(
    "scheduler.actions",
    "Планировщик: фоновые команды и агенты-помощники с пробуждением по результату",
)

/** Immutable task graphs; requires both the scheduler and background actions. */
public val SchedulerTaskGraphs: FeatureToggle.Flag = FeatureToggle.Flag(
    "scheduler.task_graphs",
    "Планировщик: графы зависимых и параллельных задач",
)

/** Names of the hosted scheduler tools and their arguments. */
public object SchedulerTools {
    /** Schedules a wake of the calling session; the agent ends its turn afterwards. */
    public const val SLEEP: String = "scheduler_sleep"

    /** Publishes a named signal on the bus. */
    public const val SIGNAL: String = "scheduler_signal"

    /** Cancels a pending wake of the calling session. */
    public const val CANCEL: String = "scheduler_cancel"

    /** Lists the calling session's wakes and the keys it can wait for. */
    public const val LIST: String = "scheduler_list"

    /** Starts a background command or helper agent whose result is published as an action event. */
    public const val START_ACTION: String = "scheduler_start_action"

    /** Argument names. */
    public object Arguments {
        /** ISO-8601 instant of a deadline. */
        public const val AT: String = "at"

        /** Seconds from now of a deadline (a timer). */
        public const val AFTER_SECONDS: String = "after_seconds"

        /** Event keys to wake on. */
        public const val EVENTS: String = "events"

        /** What to do after waking. */
        public const val NOTE: String = "note"

        /** Signal name. */
        public const val NAME: String = "name"

        /** Signal payload. */
        public const val PAYLOAD: String = "payload"

        /** Wake id to cancel. */
        public const val WAKE_ID: String = "wake_id"

        /** Action kind: `command` or `agent`. */
        public const val KIND: String = "kind"

        /** Shell command of a command action. */
        public const val COMMAND: String = "command"

        /** Time limit of a command action. */
        public const val TIMEOUT_SECONDS: String = "timeout_seconds"

        /** Task of a helper agent. */
        public const val PROMPT: String = "prompt"

        /** Title of a helper agent's chat. */
        public const val TITLE: String = "title"

        /** When set, the caller sleeps until the action finishes with this note. */
        public const val WAKE_NOTE: String = "wake_note"
    }

    /** Action kinds of [START_ACTION]. */
    public object Kinds {
        /** A shell command in the project. */
        public const val COMMAND: String = "command"

        /** A helper agent in a new session. */
        public const val AGENT: String = "agent"
    }
}
