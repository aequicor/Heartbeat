package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/** Root-scoped family and the certainty of its activity count. */
@Immutable
data class SessionTreeUi(val sessions: ImmutableList<SubSessionUi>, val activeCount: Int, val isActivityKnown: Boolean)

/** Selection identity prevents a previous child transcript flashing during a switch. */
@Immutable
data class NativeTranscriptUi(val key: String, val messages: ImmutableList<MessageUi>)
