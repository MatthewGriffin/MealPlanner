package com.example.chatgpt_mealplanner.core.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Call first in a catch-all handler, so cancelling a coroutine still stops it. A timeout from an
 * inner `withTimeout` doesn't cancel this coroutine, so it still reaches the handler.
 */
suspend fun rethrowIfCancelled() = currentCoroutineContext().ensureActive()
