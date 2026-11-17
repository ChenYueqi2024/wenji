package org.wenji.study

class ApiFailure(val status: Int, message: String) : Exception(message)

// A queued explanation may have been persisted before the engine received it.
// Reading current state is safe; spending model quota again requires a new confirmation.
internal fun mayAutoRetry(path: String): Boolean = !path.endsWith("/explain")

/** A rejected outbox item must not prevent reading the server's current state. */
internal suspend fun syncPendingAndRefresh(
    syncPending: suspend () -> Unit,
    refreshState: suspend () -> Unit,
): ApiFailure? {
    val pendingFailure = try {
        syncPending()
        null
    } catch (failure: ApiFailure) {
        failure
    }
    // Transport, cancellation, and local persistence failures still propagate.
    refreshState()
    return pendingFailure
}
