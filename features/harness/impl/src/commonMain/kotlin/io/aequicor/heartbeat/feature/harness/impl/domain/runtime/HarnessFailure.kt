package io.aequicor.heartbeat.feature.harness.impl.domain.runtime

import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.CancellationException

/** Safe boundary value: never retains a user exception, source, message, stack or cause. */
internal class ScriptFailure : Exception("Harness script failed")

/** The value can contain private script data, so even a successful attempt has a redacted representation. */
internal sealed interface HarnessAttempt<out T> {
    data class Success<T>(val value: T) : HarnessAttempt<T> {
        override fun toString(): String = "HarnessAttempt.Success(***)"
    }

    data class Failure(val error: ScriptFailure) : HarnessAttempt<Nothing>
}

/**
 * Catches the documented user-code errors, preserving cancellation and fatal platform failures. Logging only
 * attaches a newly constructed safe exception; the original object can contain arbitrary private script data.
 */
internal suspend fun <T> captureHarnessFailure(block: suspend () -> T): HarnessAttempt<T> =
    capturePlatformHarnessFailure(block).fold(
        onSuccess = { HarnessAttempt.Success(it) },
        onFailure = { error ->
            Log.tag("HarnessRuntime").w(harnessScriptFailure(error)) { "Script callback failed" }
            HarnessAttempt.Failure(ScriptFailure())
        },
    )

/** Also used by activation-owned background jobs; fatal failures and cancellation are always rethrown. */
internal fun harnessScriptFailure(error: Throwable): ScriptFailure {
    error.rethrowFatalHarnessFailure()
    return ScriptFailure()
}

/**
 * Scripting hosts may return exceptions inside diagnostics instead of throwing them. Apply the same fatal-error
 * rule there before mapping a result to a normal script failure. Wrapper exceptions cannot hide a fatal cause.
 */
internal fun Throwable.rethrowFatalHarnessFailure() {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is CancellationException || !current.isRecoverableHarnessFailure()) throw current
        current = current.cause
    }
}

/** Platform error taxonomy, with no IO, mutable state or dependence on exception text. */
internal expect fun Throwable.isRecoverableHarnessFailure(): Boolean

/** Explicit platform catches exclude fatal VM errors; callers immediately sanitize any returned exception. */
internal expect suspend fun <T> capturePlatformHarnessFailure(block: suspend () -> T): Result<T>
