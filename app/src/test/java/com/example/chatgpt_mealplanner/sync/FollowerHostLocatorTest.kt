package com.example.chatgpt_mealplanner.sync

import com.example.chatgpt_mealplanner.core.sync.follower.engine.FollowerHostLocator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerHostLocatorTest {
    @Test
    fun candidatesAreTheOtherAddressesOnTheSameNetwork_exceptOursAndTheKnownOne() {
        val list = FollowerHostLocator.candidates("192.168.1.42", "192.168.1.20")
        assertEquals(252, list.size)
        assertTrue("192.168.1.1" in list && "192.168.1.254" in list)
        assertFalse("192.168.1.42" in list)
        assertFalse("192.168.1.20" in list)
        assertTrue(list.all { it.startsWith("192.168.1.") })
    }

    @Test
    fun badAddressGivesNoCandidates() = assertTrue(FollowerHostLocator.candidates("not-an-ip", null).isEmpty())

    @Test
    fun withoutAContextOrClientNothingIsScanned() = runTest {
        assertNull(FollowerHostLocator(null, null).findHost(8443, "SHA256:00", "192.168.1.20"))
    }
}
