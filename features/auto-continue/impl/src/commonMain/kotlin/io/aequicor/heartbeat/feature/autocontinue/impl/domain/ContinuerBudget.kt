package io.aequicor.heartbeat.feature.autocontinue.impl.domain

/** Character budgets of the dialog sent to the continuer. */
internal object ContinuerBudget {
    /** Whole rendered dialog. */
    const val DIALOG_CHARS: Int = 16_000

    /** The first human message: the task. */
    const val TASK_CHARS: Int = 4_000

    /** The last agent reply, which the continuer answers. */
    const val LAST_REPLY_CHARS: Int = 6_000

    /** Any other human message. */
    const val HUMAN_CHARS: Int = 2_000

    /** Older agent replies and autopilot messages. */
    const val SNIPPET_CHARS: Int = 600

    /** The plan note of the planning mode. */
    const val PLAN_CHARS: Int = 3_000

    /** Answer of the continuer. */
    const val ANSWER_CHARS: Int = 8_000

    /** Label of one option. */
    const val LABEL_CHARS: Int = 40

    /** Text of one option. */
    const val OPTION_CHARS: Int = 4_000

    /** Reason of the verdict. */
    const val REASON_CHARS: Int = 300
}
