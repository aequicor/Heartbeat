package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import kotlinx.coroutines.flow.Flow

/** A model the user enabled, with its catalog title and advertised efforts. */
internal data class DirectoryModel(
    val target: EngineTarget,
    val title: String,
    val engineTitle: String,
    val connection: String,
    val efforts: List<String>,
    val defaultEffort: String?,
)

/** An enabled connection with its engine and credential names. */
internal data class DirectoryConnection(val binding: EngineBindingId, val title: String)

/** What settings show: enabled models and connections. */
internal data class Directory(
    val models: List<DirectoryModel> = emptyList(),
    val connections: List<DirectoryConnection> = emptyList(),
)

/** Enabled models and connections of the profile, for the settings screens. */
internal fun interface ModelDirectory {
    /** The directory and its changes; reads cached catalogs only. */
    fun observe(): Flow<Directory>
}
