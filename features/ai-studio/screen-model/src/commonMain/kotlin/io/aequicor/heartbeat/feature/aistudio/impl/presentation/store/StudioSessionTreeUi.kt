@file:UseSerializers(ImmutableListSerializer::class, ImmutableSetSerializer::class, ImmutableMapSerializer::class)

package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableListSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableMapSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.ImmutableSetSerializer
import io.aequicor.heartbeat.feature.aistudio.screenmodel.SubSessionUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Root-scoped family and the certainty of its activity count. */
@Serializable
@SerialName("SessionTreeUi")
@Immutable
data class SessionTreeUi(val sessions: ImmutableList<SubSessionUi>, val activeCount: Int, val isActivityKnown: Boolean)

/** Selection identity prevents a previous child transcript flashing during a switch. */
@Serializable
@SerialName("NativeTranscriptUi")
@Immutable
data class NativeTranscriptUi(val key: String, val messages: ImmutableList<MessageUi>)
