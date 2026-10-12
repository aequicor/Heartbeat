package io.aequicor.heartbeat.feature.aiengine.facade.api

/**
 * Prompt shaping for engines that rebuild their prompt from the transcript every turn (Koog). Tool results of all
 * but the newest [keepRecentTurns] turns are replaced by their first [toolStubChars] characters and a note
 * (observation masking); reasoning never reaches such prompts anyway. Masking advances [maskStepTurns] turns at a
 * time, so the prompt prefix stays the same between steps and provider prompt caches keep working.
 */
public data class ContextPolicy(val keepRecentTurns: Int, val toolStubChars: Int, val maskStepTurns: Int = 1) {
    init {
        require(keepRecentTurns > 0 && toolStubChars >= 0 && maskStepTurns > 0)
    }
}

/**
 * Profile multibinding that shapes rebuilt prompts; resolved lazily. Null leaves the session's prompt verbatim; the
 * first non-null policy applies. Reading performs no IO.
 */
public fun interface ContextPolicyProvider {
    /** The policy for [session], or null. */
    public suspend fun policy(session: SessionRef): ContextPolicy?
}
