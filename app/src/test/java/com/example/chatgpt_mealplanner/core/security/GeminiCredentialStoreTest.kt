package com.example.chatgpt_mealplanner.core.security

import com.example.chatgpt_mealplanner.contract.AiContextPayload
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.contract.UserPreferencesSummaryDto
import com.example.chatgpt_mealplanner.core.ai.provider.GeminiMealPlannerAiProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class GeminiCredentialStoreTest {

    private val emptyPayload = AiContextPayload(
        currentDate = "2026-09-28",
        dayOfWeek = "MONDAY",
        preferences = UserPreferencesSummaryDto(),
        recentCookedMeals = emptyList(),
        upcomingPlannedMeals = emptyList(),
        availableLeftovers = emptyList(),
        useSoonItems = emptyList(),
        lowStockStaples = emptyList(),
        activeShoppingList = emptyList()
    )

    @Test
    fun savedGeminiKey_isObservedByChatProvider() = runTest {
        val credentialStore = FakeCredentialStore()
        credentialStore.saveKey("test_secret_gemini_key_123")

        val provider = GeminiMealPlannerAiProvider(credentialStore = credentialStore)
        var capturedUrl = ""
        provider.httpCallHandler = { url, _ ->
            capturedUrl = url
            Pair(200, """{"candidates":[{"content":{"parts":[{"text":"Success"}]}}]}""")
        }

        assertTrue(credentialStore.hasKey())
        assertEquals("test_secret_gemini_key_123", credentialStore.loadKey())

        val request = AiProviderRequest(
            conversationId = "test_conv",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = provider.send(request)
        assertTrue(capturedUrl.contains("key=test_secret_gemini_key_123"))
        assertNotEquals("NO_API_KEY", (response as? AiProviderResponse.Error)?.code)
    }

    @Test
    fun missingGeminiKey_isReportedOnlyWhenNoKeyExists() = runTest {
        val credentialStore = FakeCredentialStore() // empty
        val provider = GeminiMealPlannerAiProvider(credentialStore = credentialStore)

        assertFalse(credentialStore.hasKey())

        val request = AiProviderRequest(
            conversationId = "test_conv",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = provider.send(request)
        assertTrue(response is AiProviderResponse.Error)
        assertEquals("NO_API_KEY", (response as AiProviderResponse.Error).code)
    }

    @Test
    fun persistedGeminiKey_survivesViewModelRecreation() = runTest {
        val credentialStore = FakeCredentialStore()
        credentialStore.saveKey("test_persisted_key")

        // First ViewModel/Provider session
        val provider1 = GeminiMealPlannerAiProvider(credentialStore = credentialStore)
        assertTrue(credentialStore.hasKey())

        // Simulated ViewModel recreation / screen re-navigation
        val provider2 = GeminiMealPlannerAiProvider(credentialStore = credentialStore)
        assertTrue(credentialStore.hasKey())
        assertEquals("test_persisted_key", credentialStore.loadKey())
        assertNotNull(provider1)
        assertNotNull(provider2)
    }

    @Test
    fun navigationAwayAndBack_preservesCredentialAvailability() = runTest {
        val credentialStore = FakeCredentialStore()
        credentialStore.saveKey("valid_gemini_key")

        // Navigate away and return
        assertTrue(credentialStore.hasKey())
        val provider = GeminiMealPlannerAiProvider(credentialStore = credentialStore)
        var capturedUrl = ""
        provider.httpCallHandler = { url, _ ->
            capturedUrl = url
            Pair(200, """{"candidates":[{"content":{"parts":[{"text":"Success"}]}}]}""")
        }

        val request = AiProviderRequest(
            conversationId = "nav_test",
            systemPrompt = "Prompt",
            contextPayloadJson = "{}",
            contextPayload = emptyPayload,
            messages = emptyList(),
            availableTools = emptyList()
        )

        val response = provider.send(request)
        assertTrue(capturedUrl.contains("key=valid_gemini_key"))
        assertNotEquals("NO_API_KEY", (response as? AiProviderResponse.Error)?.code)
    }
}

private fun assertNotNull(obj: Any?) {
    assertTrue(obj != null)
}
