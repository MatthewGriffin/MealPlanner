package com.example.chatgpt_mealplanner.sync

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest
import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.backup.BackupManager
import com.example.chatgpt_mealplanner.core.database.DatabaseTransactionRunner
import com.example.chatgpt_mealplanner.core.database.dao.ChatDao
import com.example.chatgpt_mealplanner.core.security.CredentialStore
import com.example.chatgpt_mealplanner.core.sync.AppRoleResolver
import com.example.chatgpt_mealplanner.core.sync.follower.worker.FollowerSyncScheduler
import com.example.chatgpt_mealplanner.feature.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PantryPhase6SafExportTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeChatDao : ChatDao {
        override fun observeAllConversations(): Flow<List<com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity>> = flowOf(emptyList())
        override suspend fun getConversationById(id: String) = null
        override suspend fun insertOrUpdateConversation(conversation: com.example.chatgpt_mealplanner.core.database.entity.ChatConversationEntity) {}
        override suspend fun deleteConversation(id: String) {}
        override fun observeMessagesForConversation(conversationId: String): Flow<List<com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity>> = flowOf(emptyList())
        override suspend fun getMessagesForConversation(conversationId: String): List<com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity> = emptyList()
        override suspend fun insertMessage(message: com.example.chatgpt_mealplanner.core.database.entity.ChatMessageEntity) {}
        override suspend fun deleteAllMessagesForConversation(conversationId: String) {}
        override suspend fun insertOrUpdateToolExecutionLog(log: com.example.chatgpt_mealplanner.core.database.entity.AiToolExecutionLogEntity) {}
        override suspend fun getToolExecutionLog(conversationId: String, toolCallId: String) = null
        override suspend fun deleteLogsOlderThan(timestamp: Long) {}
        override suspend fun getPendingAiMutation(conversationId: String) = null
        override suspend fun insertOrUpdatePendingAiMutation(entity: com.example.chatgpt_mealplanner.core.database.entity.PendingAiMutationEntity) {}
        override suspend fun deletePendingAiMutation(conversationId: String) {}
        override suspend fun getActiveProposal(conversationId: String) = null
        override suspend fun insertOrUpdateActiveProposal(entity: com.example.chatgpt_mealplanner.core.database.entity.ActiveProposalEntity) {}
        override suspend fun deleteActiveProposal(conversationId: String) {}
    }

    private class FakeAiProvider : MealPlannerAiProvider {
        override suspend fun send(request: AiProviderRequest): AiProviderResponse {
            return AiProviderResponse.FinalResponse("fake")
        }
        override suspend fun continueWithToolResults(request: AiContinueWithToolsRequest): AiProviderResponse {
            return AiProviderResponse.FinalResponse("fake")
        }
    }

    private class FakeCredentialStore : CredentialStore {
        override fun hasKey(): Boolean = false
        override fun saveKey(key: String) {}
        override fun loadKey(): String? = null
        override fun deleteKey() {}
    }

    private class FakeAppRoleResolver(private val isMainRole: Boolean) : AppRoleResolver(
        com.example.chatgpt_mealplanner.core.sync.follower.config.FollowerPairingStore(ApplicationProvider.getApplicationContext())
    ) {
        override suspend fun isMainAsync(): Boolean = isMainRole
        override fun isMain(): Boolean = isMainRole
        override suspend fun isFollowerAsync(): Boolean = !isMainRole
        override fun isFollower(): Boolean = !isMainRole
    }

    private fun createViewModel(isMainRole: Boolean = true): SettingsViewModel {
        val noOpRunner = object : DatabaseTransactionRunner {
            override suspend fun <T> runInTransaction(block: suspend () -> T): T = block()
        }
        return SettingsViewModel(
            context = context,
            credentialStore = FakeCredentialStore(),
            provider = FakeAiProvider(),
            chatDao = FakeChatDao(),
            backupManager = BackupManager(null, noOpRunner),
            followerSyncScheduler = FollowerSyncScheduler(dagger.Lazy { error("Not called") }),
            appRoleResolver = FakeAppRoleResolver(isMainRole)
        )
    }

    @Test
    fun exportInventoryToUri_nullUri_setsExportCancelledMessage() = runTest(testDispatcher) {
        val viewModel = createViewModel(isMainRole = true)

        viewModel.exportInventoryToUri(context, null, testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Export cancelled.", viewModel.uiState.value.exportMessage)
    }

    @Test
    fun exportInventoryToUri_followerRole_rejectsExportWithMessage() = runTest(testDispatcher) {
        val exportFile = File(context.cacheDir, "test_follower_export.json")
        if (exportFile.exists()) exportFile.delete()

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
        val viewModel = createViewModel(isMainRole = false)

        viewModel.exportInventoryToUri(context, uri, testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Export file MUST NOT be created on disk for Follower role", exportFile.exists())
        assertEquals("Export failed: Inventory export is available on Main device only.", viewModel.uiState.value.exportMessage)
        assertFalse("isMainRole MUST resolve to false for Follower role", viewModel.uiState.value.isMainRole)
    }

    @Test
    fun exportInventoryToUri_mainRole_writesUtf8JsonAndReportsSuccess() = runTest(testDispatcher) {
        val exportFile = File(context.cacheDir, "test_inventory_export.json")
        if (!exportFile.exists()) exportFile.createNewFile()

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
        val viewModel = createViewModel(isMainRole = true)

        viewModel.exportInventoryToUri(context, uri, testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Export file MUST exist on disk after writing", exportFile.exists())
        val writtenContent = exportFile.readText(Charsets.UTF_8)
        assertTrue("Exported JSON MUST contain pantry-planner-export-v1 version", writtenContent.contains("pantry-planner-export-v1"))
        assertTrue("Export message MUST indicate success", viewModel.uiState.value.exportMessage!!.startsWith("Inventory export saved successfully"))
        assertTrue("isMainRole MUST resolve to true for Main role", viewModel.uiState.value.isMainRole)

        exportFile.delete()
    }
}
