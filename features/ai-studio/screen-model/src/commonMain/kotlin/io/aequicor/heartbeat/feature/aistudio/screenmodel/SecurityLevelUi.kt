package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.serialization.Serializable

/** Confirmation level saved for ordinary native chats; demo and organism modes have their own approvals. */
@Serializable
enum class SecurityLevelUi { Ask, Automatic, Trust }
