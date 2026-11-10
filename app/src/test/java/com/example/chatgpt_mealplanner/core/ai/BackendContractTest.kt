package com.example.chatgpt_mealplanner.core.ai

import com.example.chatgpt_mealplanner.contract.AI_CONTRACT_VERSION
import com.example.chatgpt_mealplanner.contract.AiToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class BackendContractTest {

    @Test
    fun verifyContractVersion_andToolIdSymmetry() {
        assertEquals("ai-contract-v2", AI_CONTRACT_VERSION)
        assertEquals(34, AiToolId.entries.size)

        AiToolId.entries.forEach { toolId ->
            val resolved = AiToolId.fromToolName(toolId.toolName)
            assertNotNull(resolved)
            assertEquals(toolId, resolved)
        }
    }
}
