package io.aequicor.heartbeat.feature.organicai.impl.data

import io.aequicor.heartbeat.feature.organicai.api.Organism
import io.aequicor.heartbeat.feature.organicai.api.SessionRelease
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeAttempt
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One atomic record owns both the machine snapshot and its native attempts. Legacy records have no release proof. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class OrganicJournalRecord(
    val organism: Organism,
    @EncodeDefault val schema: Int = 1,
    val attempts: List<OrganicNativeAttempt> = emptyList(),
    val isReleaseProven: Boolean = false,
    val isNativeTracked: Boolean = true,
) {
    init {
        require(schema == 1) { "Unsupported organism journal schema" }
        require(attempts.map { it.claim.token }.distinct().size == attempts.size) { "Duplicate native claim" }
        require(attempts.map { it.claim.request }.distinct().size == attempts.size) { "Duplicate native request" }
        require(!isReleaseProven || attempts.all { it.isQuiescent }) { "Unsettled native release proof" }
    }

    fun restored(): Organism = if (organism.isDeveloping) {
        organism
    } else {
        organism.copy(sessions = if (isReleaseProven) SessionRelease.Released else SessionRelease.Unconfirmed)
    }
}

internal val OrganicJournalJson = Json { ignoreUnknownKeys = true }

/** Decode the old plain snapshot without manufacturing native evidence; the next transaction writes the envelope. */
internal fun decodeOrganicRecord(raw: String): OrganicJournalRecord {
    val element = OrganicJournalJson.parseToJsonElement(raw)
    return if ("schema" in element.jsonObject) {
        OrganicJournalJson.decodeFromJsonElement(OrganicJournalRecord.serializer(), element)
    } else {
        OrganicJournalRecord(
            OrganicJournalJson.decodeFromJsonElement(Organism.serializer(), element),
            isNativeTracked = false,
        )
    }
}
