package org.wenji.study

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncRefreshTest {
    @Test fun explanationIsNeverAutomaticallySentDuringRefreshOrRestart() {
        assertFalse(mayAutoRetry("/questions/question-1/explain"))
        assertFalse(mayAutoRetry("/questions/question-1/reference"))
        assertTrue(mayAutoRetry("/sources"))
        assertTrue(mayAutoRetry("/cards/card-1/attempts"))
    }
    @Test fun successfulSyncFetchesAndCachesStateAfterPendingWork() = runBlocking {
        val events = mutableListOf<String>()
        val failure = syncPendingAndRefresh(
            syncPending = { events += "write"; events += "question" },
            refreshState = { events += "fetch"; events += "cache" },
        )
        assertNull(failure)
        assertEquals(listOf("write", "question", "fetch", "cache"), events)
    }

    @Test fun rejectedWriteStillRefreshesWithoutSendingLaterItems() = runBlocking {
        val rejected = ApiFailure(409, "模型配置已变化，请重新确认")
        val events = mutableListOf<String>()
        val pending = mutableListOf("saved-write", "rejected-write", "later-write")
        var cachedState = "old"
        val failure = syncPendingAndRefresh(
            syncPending = {
                for (write in pending.toList()) {
                    events += write
                    if (write == "rejected-write") throw rejected
                    pending.remove(write)
                }
                events += "question"
            },
            refreshState = { events += "refresh"; cachedState = "latest" },
        )
        assertSame(rejected, failure)
        assertEquals(listOf("saved-write", "rejected-write", "refresh"), events)
        assertEquals(listOf("rejected-write", "later-write"), pending)
        assertEquals("latest", cachedState)
    }

    @Test fun rejectedQuestionStillRefreshesAndReportsItsFailure() = runBlocking {
        val rejected = ApiFailure(422, "疑问无效")
        val events = mutableListOf<String>()
        val failure = syncPendingAndRefresh(
            syncPending = { events += "writes"; events += "question"; throw rejected },
            refreshState = { events += "refresh" },
        )
        assertSame(rejected, failure)
        assertEquals(listOf("writes", "question", "refresh"), events)
    }

    @Test fun transportFailurePropagatesWithoutReportingFreshState() = runBlocking {
        val disconnected = IOException("connection refused")
        var refreshed = false
        val failure = runCatching {
            syncPendingAndRefresh(
                syncPending = { throw disconnected },
                refreshState = { refreshed = true },
            )
        }.exceptionOrNull()
        assertSame(disconnected, failure)
        assertFalse(refreshed)
    }

    @Test fun cancellationPropagatesWithoutStartingAnotherRequest() = runBlocking {
        val cancelled = CancellationException("cancelled")
        var refreshed = false
        val failure = runCatching {
            syncPendingAndRefresh(
                syncPending = { throw cancelled },
                refreshState = { refreshed = true },
            )
        }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertFalse(refreshed)
    }

    @Test fun failedStateFetchIsNotHiddenByAnEarlierRejection() = runBlocking {
        val unavailable = ApiFailure(503, "state unavailable")
        val failure = runCatching {
            syncPendingAndRefresh(
                syncPending = { throw ApiFailure(409, "conflict") },
                refreshState = { throw unavailable },
            )
        }.exceptionOrNull()
        assertSame(unavailable, failure)
    }

    @Test fun failedPersistencePropagatesWithoutReportingSyncSuccess() = runBlocking {
        val storageFailure = IOException("disk full")
        val failure = runCatching {
            syncPendingAndRefresh(
                syncPending = {},
                refreshState = { throw storageFailure },
            )
        }.exceptionOrNull()
        assertSame(storageFailure, failure)
    }
}
