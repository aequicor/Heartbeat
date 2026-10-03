package io.aequicor.heartbeat.core.logging

/**
 * Marks a handler invoked per streaming delta, revision, polling tick or similar frequent event.
 *
 * Routine diagnostics in this handler and its inline lambdas must use [Log.v]; Detekt rejects
 * DEBUG/INFO calls. Log meaningful summaries outside the handler, at an operation boundary.
 * Warnings and errors keep their severity and throwable. Mark extracted frequent helpers too:
 * the syntax-only rule does not follow calls or inherited annotations.
 *
 * This source marker does not throttle logs at runtime. VERBOSE is enabled explicitly by trace mode.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.SOURCE)
public annotation class HighFrequency
