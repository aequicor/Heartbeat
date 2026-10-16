package io.aequicor.heartbeat.feature.aiengine.controlpanel.impl.presentation.store

/** A project of the panel's engine: opening it, editing and saving its files, deciding its trust. */
sealed interface ProjectIntent : EngineControlPanelIntent {
    /** Reads the files of the saved project [project] and shows them. */
    data class Open(val project: String) : ProjectIntent

    /** Returns to the panel overview, dropping unsaved changes of the project. */
    data object Close : ProjectIntent

    /** Shows the project file [file] as a whole. */
    data class OpenFile(val file: String) : ProjectIntent

    /** Returns from a project file to the project. */
    data object CloseFile : ProjectIntent

    /** Changes the value of [field] in the project draft. */
    data class EditField(val field: String, val input: FieldInputUi) : ProjectIntent

    /** Replaces the whole text of the project file [file]. */
    data class EditFile(val file: String, val text: String) : ProjectIntent {
        override fun toString(): String = "EditFile(file=$file)"
    }

    /** Writes the draft into the project's files. */
    data object Save : ProjectIntent

    /** Drops the draft. */
    data object Discard : ProjectIntent

    /** Drops the draft and reads the project's files anew. */
    data object Reload : ProjectIntent

    /** Decides whether the project's executable configuration loads. */
    data class SetTrust(val trust: ProjectTrustUi) : ProjectIntent
}
