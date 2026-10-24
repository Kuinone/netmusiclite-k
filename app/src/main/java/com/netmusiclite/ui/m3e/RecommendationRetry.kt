package com.netmusiclite.ui.m3e

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Retry empty, duplicate, or failed batches serially; the deadline also runs during slow IO. */
internal suspend fun retryRecommendationsUntilProgress(
    fetch: suspend () -> Boolean,
    onLoading: (Boolean) -> Unit,
    onManualRetryAvailable: (Boolean) -> Unit,
    retryDelayMillis: Long = 1_200,
    manualRetryDelayMillis: Long = 15_000,
) = coroutineScope {
    var deadlineReached = false
    onManualRetryAvailable(false)
    val deadline = launch {
        delay(manualRetryDelayMillis)
        deadlineReached = true
        onManualRetryAvailable(true)
    }
    try {
        while (isActive && !deadlineReached) {
            onLoading(true)
            val progressed = try {
                fetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                onLoading(false)
            }
            if (progressed) {
                onManualRetryAvailable(false)
                break
            }
            // No busy loop when the API repeatedly returns an empty or already-seen batch.
            if (!deadlineReached) delay(retryDelayMillis)
        }
    } finally {
        deadline.cancel()
    }
}
