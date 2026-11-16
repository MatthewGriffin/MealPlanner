package com.example.chatgpt_mealplanner.core.ai.provider

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyAccountRegisterTest {
    private fun account(server: MockWebServer) = object : ProxyAccount(null) {
        override val baseUrl = server.url("/").toString().removeSuffix("/")
    }

    @Test
    fun integrityRefusal_isRemembered_andNoAccountIsMade() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"nonce":"abc"}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"no","code":"integrity"}"""))
            val a = account(server)
            assertNull(a.token())
            assertTrue(a.refusedByIntegrity)
        }
    }

    @Test
    fun otherFailures_areNotMistakenForAnIntegrityRefusal() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"nonce":"abc"}"""))
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Too many"}"""))
            val a = account(server)
            assertNull(a.token())
            assertFalse(a.refusedByIntegrity)
        }
    }

    @Test
    fun registersWithoutAToken_whenPlayCannotProvideOne() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"nonce":"abc"}"""))
            server.enqueue(MockResponse().setBody("""{"token":"user.sig"}"""))
            val a = account(server)
            assertEquals("user.sig", a.token())
            assertFalse(a.refusedByIntegrity)
            server.takeRequest()
            val register = server.takeRequest()
            assertEquals("/v1/register", register.path)
            assertEquals(0L, register.bodySize)
            assertNotNull(register)
        }
    }
}
