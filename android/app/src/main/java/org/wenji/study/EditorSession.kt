package org.wenji.study

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class RevisionDraft(val baseRevision: Int, val text: String)

/** ViewModel-owned editor state, intentionally absent from saved-state Bundles. */
internal class EditorSession {
    var importText by mutableStateOf(""); private set
    private val revisions = mutableStateMapOf<String, RevisionDraft>()

    fun updateImport(text: String) { importText = text.take(500_000) }
    fun finishImport(submittedText: String) {
        if (importText == submittedText) importText = ""
    }
    fun revision(sourceId: String): RevisionDraft? = revisions[sourceId]
    fun beginRevision(sourceId: String, revision: Int, text: String) {
        // Opening an existing draft must never silently rebase it after a refresh.
        if (sourceId !in revisions) revisions[sourceId] = RevisionDraft(revision, text)
    }
    fun updateRevision(sourceId: String, text: String) {
        val draft = revisions[sourceId] ?: return
        revisions[sourceId] = draft.copy(text = text.take(500_000))
    }
    fun discardRevision(sourceId: String) { revisions.remove(sourceId) }
    fun finishRevision(sourceId: String, submitted: RevisionDraft) {
        // Preserve edits made while an earlier request was in flight.
        if (revisions[sourceId] == submitted) revisions.remove(sourceId)
    }
}
