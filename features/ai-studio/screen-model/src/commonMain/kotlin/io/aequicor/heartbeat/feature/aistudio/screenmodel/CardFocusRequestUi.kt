package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One request to reveal a specific inline card in the owning chat; newer sequences replace older requests. */
@Serializable
@SerialName("CardFocusRequestUi")
@Immutable
data class CardFocusRequestUi(val chatId: String, val messageId: String, val cardId: String, val sequence: Long)
