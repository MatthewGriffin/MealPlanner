package com.example.chatgpt_mealplanner.core.sync.server

import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NettyShutdownGuardTest {
    private fun thread(name: String) = Thread({}, name)

    @Test
    fun ignoresOnlyTheNettyShutdownRace() {
        val err = RejectedExecutionException("event executor terminated")
        assertTrue(NettyShutdownGuard.isHarmlessShutdownError(thread("eventLoopGroupProxy-3-1"), err))
        // Same error on another thread, a different message, or a different exception type is a real problem.
        assertFalse(NettyShutdownGuard.isHarmlessShutdownError(thread("main"), err))
        assertFalse(NettyShutdownGuard.isHarmlessShutdownError(thread("eventLoopGroupProxy-3-1"), RejectedExecutionException("queue full")))
        assertFalse(NettyShutdownGuard.isHarmlessShutdownError(thread("eventLoopGroupProxy-3-1"), IllegalStateException("event executor terminated")))
    }
}
