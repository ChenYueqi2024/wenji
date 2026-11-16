package org.wenji.study

import org.junit.Assert.*
import org.junit.Test

class EditorSessionTest {
    @Test fun revisionDraftKeepsOriginalBaseAfterRemoteRefresh() {
        val session = EditorSession()
        session.beginRevision("source", 1, "原文")
        session.updateRevision("source", "我的未提交修改")
        session.beginRevision("source", 2, "远端新版本")
        assertEquals(RevisionDraft(1, "我的未提交修改"), session.revision("source"))
    }

    @Test fun completingOldRequestDoesNotEraseNewerDraftEdits() {
        val session = EditorSession()
        session.beginRevision("source", 3, "已提交的修改")
        val submitted = session.revision("source")!!
        session.updateRevision("source", "等待期间的新修改")
        session.finishRevision("source", submitted)
        assertEquals(RevisionDraft(3, "等待期间的新修改"), session.revision("source"))
        session.finishRevision("source", session.revision("source")!!)
        assertNull(session.revision("source"))
    }

    @Test fun largeImportStaysInSessionAndOldCompletionCannotClearNewInput() {
        val session = EditorSession()
        val largeText = "课".repeat(500_000)
        session.updateImport(largeText)
        assertEquals(largeText, session.importText)
        session.updateImport("新的草稿")
        session.finishImport(largeText)
        assertEquals("新的草稿", session.importText)
        session.finishImport("新的草稿")
        assertEquals("", session.importText)
    }

    @Test fun cancelAllowsExplicitlyOpeningLatestRevision() {
        val session = EditorSession()
        session.beginRevision("source", 1, "旧稿")
        session.discardRevision("source")
        session.beginRevision("source", 2, "新原文")
        assertEquals(RevisionDraft(2, "新原文"), session.revision("source"))
    }
}
