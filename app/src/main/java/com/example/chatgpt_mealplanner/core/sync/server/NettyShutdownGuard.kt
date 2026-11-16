package com.example.chatgpt_mealplanner.core.sync.server

import java.util.concurrent.RejectedExecutionException

/**
 * Stopping the household sync server while a connection is still closing makes Ktor's Netty layer throw
 * "event executor terminated" on one of its own threads, where nothing can catch it and the whole app would crash.
 * It only means the server is already shut down, so this ignores exactly that error and passes everything else on.
 */
object NettyShutdownGuard {
    fun isHarmlessShutdownError(thread: Thread, error: Throwable): Boolean =
        error is RejectedExecutionException &&
            error.message?.contains("event executor terminated", ignoreCase = true) == true &&
            thread.name.startsWith("eventLoopGroupProxy")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (isHarmlessShutdownError(thread, error)) return@setDefaultUncaughtExceptionHandler
            previous?.uncaughtException(thread, error)
        }
    }
}
