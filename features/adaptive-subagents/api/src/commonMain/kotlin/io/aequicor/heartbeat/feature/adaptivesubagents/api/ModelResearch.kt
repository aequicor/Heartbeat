package io.aequicor.heartbeat.feature.adaptivesubagents.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget

/** The model a description is researched for: its key, catalog title and advertised reasoning efforts. */
public data class ResearchSubject(
    val key: ModelKey,
    val title: String,
    val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
)

/** Where a research run is. */
public enum class ResearchStage {
    /** Waiting for its turn. */
    Queued,

    /** Reading catalog facts (models.dev). */
    Facts,

    /** Searching the web. */
    Searching,

    /** Reading found pages. */
    Reading,

    /** The researcher model writes the description. */
    Writing,
}

/** One running research of [subject] on the [researcher] model; [sources] counts pages read so far. */
public data class ResearchJob(
    val id: String,
    val subject: ResearchSubject,
    val researcher: EngineTarget,
    val stage: ResearchStage = ResearchStage.Queued,
    val sources: Int = 0,
)

/** Why a research run produced no description. */
public enum class ResearchFailure {
    /** The model is being researched already, or [ResearchLimits.MAX_JOBS] runs are busy. */
    Busy,

    /** Neither catalog facts nor any web page could be read. */
    NoSources,

    /** The researcher model could not answer (unsupported, busy, timed out). */
    ResearcherUnavailable,

    /** The researcher's answer was not a valid description, even after one repair. */
    InvalidAnswer,

    /** The user cancelled it. */
    Cancelled,

    /** An unexpected failure. */
    Failed,
}

/** Bounds of research. */
public object ResearchLimits {
    /** Runs at once in a profile. */
    public const val MAX_JOBS: Int = 2
}
