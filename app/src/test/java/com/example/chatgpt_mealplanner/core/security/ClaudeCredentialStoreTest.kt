package com.example.chatgpt_mealplanner.core.security

import com.example.chatgpt_mealplanner.contract.AiChatMessage
import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.provider.ClaudeMealPlannerAiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** The provider reads the saved key on every request, so a key saved or replaced in Settings is used at once. */
class ClaudeCredentialStoreTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[{"type":"text","text":"Hi"}],
        "stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
    )

    private fun provider(store: CredentialStore) = ClaudeMealPlannerAiProvider(store, Dispatchers.Unconfined).apply {
        baseUrl = server.url("/").toString().removeSuffix("/")
        maxRetries = 0
    }

    /** The SDK sends the key as a bearer token. Older versions used x-api-key. */
    private fun keyIn(request: okhttp3.mockwebserver.RecordedRequest): String? =
        request.getHeader("x-api-key") ?: request.getHeader("Authorization")?.removePrefix("Bearer ")

    private fun request() = AiProviderRequest("conv", "Prompt", "{}", AiContextPayload(), listOf(AiChatMessage("user", "Hello")), emptyList())

    @Test
    fun theSavedKey_isSentInTheHeader_andNeverInTheUrl() = runBlocking {
        val store = FakeCredentialStore()
        store.saveKey("  sk-ant-saved  ")
        server.enqueue(ok())

        provider(store).send(request())

        val recorded = server.takeRequest()
        assertEquals("sk-ant-saved", keyIn(recorded))
        assertFalse(recorded.requestUrl.toString().contains("sk-ant-saved"))
    }

    @Test
    fun aReplacedKey_isUsedForTheNextRequest() = runBlocking {
        val store = FakeCredentialStore("sk-ant-old")
        val provider = provider(store)
        server.enqueue(ok())
        server.enqueue(ok())

        provider.send(request())
        store.replaceKey("sk-ant-new")
        provider.send(request())

        assertEquals("sk-ant-old", keyIn(server.takeRequest()))
        assertEquals("sk-ant-new", keyIn(server.takeRequest()))
    }

    @Test
    fun aDeletedKey_stopsRequests() = runBlocking {
        val store = FakeCredentialStore("sk-ant-old")
        store.deleteKey()

        val response = provider(store).send(request())

        assertEquals("NO_API_KEY", (response as AiProviderResponse.Error).code)
        assertEquals(0, server.requestCount)
    }
}
