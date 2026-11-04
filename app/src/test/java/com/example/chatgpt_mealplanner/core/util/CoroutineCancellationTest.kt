package com.example.chatgpt_mealplanner.core.util

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineCancellationTest {

    @Test
    fun cancelledCoroutine_skipsTheRestOfTheHandler() = runTest {
        var reachedFailureHandling = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } catch (e: Exception) {
                rethrowIfCancelled()
                reachedFailureHandling = true
            }
        }

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(false, reachedFailureHandling)
    }

    @Test
    fun innerTimeout_stillReachesTheHandler() = runTest {
        var handled: Exception? = null

        try {
            withTimeout(10) { delay(1_000) }
        } catch (e: Exception) {
            rethrowIfCancelled()
            handled = e
        }

        assertTrue(handled is TimeoutCancellationException)
    }

    @Test
    fun ordinaryFailure_stillReachesTheHandler() = runTest {
        var handled = false

        try {
            error("failed")
        } catch (e: Exception) {
            rethrowIfCancelled()
            handled = true
        }

        assertTrue(handled)
    }
}
