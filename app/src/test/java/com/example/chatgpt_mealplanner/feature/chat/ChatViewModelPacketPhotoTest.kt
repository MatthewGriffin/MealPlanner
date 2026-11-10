package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto
import com.example.chatgpt_mealplanner.core.ai.provider.MealPlannerAiProvider
import com.example.chatgpt_mealplanner.core.ai.validator.RecipeValidator
import com.example.chatgpt_mealplanner.core.repository.ChatRepositoryImpl
import com.example.chatgpt_mealplanner.core.service.FakeChatDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryBarcodeDao
import com.example.chatgpt_mealplanner.core.service.FakeInventoryDao
import com.example.chatgpt_mealplanner.core.service.FakeLeftoverDao
import com.example.chatgpt_mealplanner.core.service.FakeMealDao
import com.example.chatgpt_mealplanner.core.service.FakeShoppingDao
import com.example.chatgpt_mealplanner.core.service.FakeStapleRuleDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionDao
import com.example.chatgpt_mealplanner.core.service.FakeTransactionRunner
import com.example.chatgpt_mealplanner.core.service.InventoryDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.MealDomainServiceImpl
import com.example.chatgpt_mealplanner.core.service.ShoppingDomainServiceImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPacketPhotoTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sent = mutableListOf<AiProviderRequest>()
    private lateinit var chatDao: FakeChatDao
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        chatDao = FakeChatDao()
        val inventoryDao = FakeInventoryDao()
        val mealDao = FakeMealDao()
        val leftoverDao = FakeLeftoverDao()
        val shoppingDao = FakeShoppingDao()
        val stapleRuleDao = FakeStapleRuleDao()
        val transactionDao = FakeTransactionDao()
        val runner = FakeTransactionRunner(
            fakeMealDao = mealDao, fakeInventoryDao = inventoryDao, fakeTransactionDao = transactionDao,
            fakeLeftoverDao = leftoverDao, fakeShoppingDao = shoppingDao, fakeStapleRuleDao = stapleRuleDao
        )
        val inventoryService = InventoryDomainServiceImpl(inventoryDao, FakeInventoryBarcodeDao(), transactionDao, stapleRuleDao, runner)
        val mealService = MealDomainServiceImpl(mealDao, inventoryDao, transactionDao, leftoverDao, runner)
        val shoppingService = ShoppingDomainServiceImpl(shoppingDao, mealDao, inventoryDao, stapleRuleDao, runner)
        val pending = com.example.chatgpt_mealplanner.core.ai.pending.PendingMutationStore(chatDao)
        val active = com.example.chatgpt_mealplanner.core.ai.proposal.ActiveProposalStore(chatDao)
        val dispatcher = AiToolDispatcher(
            inventoryDao = inventoryDao, mealDao = mealDao, leftoverDao = leftoverDao, shoppingDao = shoppingDao,
            inventoryDomainService = inventoryService, mealDomainService = mealService, shoppingDomainService = shoppingService,
            chatDao = chatDao, pendingMutationStore = pending, activeProposalStore = active
        )
        val provider = object : MealPlannerAiProvider {
            override suspend fun send(request: AiProviderRequest): AiProviderResponse {
                sent += request
                return AiProviderResponse.FinalResponse("Thanks, I have the packet.")
            }
            override suspend fun continueWithToolResults(request: com.example.chatgpt_mealplanner.contract.AiContinueWithToolsRequest) =
                AiProviderResponse.FinalResponse("Done")
        }
        val chatRepository = ChatRepositoryImpl(chatDao)
        val orchestrator = AiMealPlannerOrchestrator(
            provider = provider,
            contextBuilder = AiContextBuilder(inventoryDao, mealDao, leftoverDao, shoppingDao, active, pending),
            toolDispatcher = dispatcher,
            recipeValidator = RecipeValidator(inventoryDao, mealDao),
            intentResolver = UserMutationIntentResolver(),
            chatRepository = chatRepository,
            pendingMutationStore = pending,
            activeProposalStore = active
        )
        viewModel = ChatViewModel(orchestrator, chatRepository, mealService)
    }

    /** The screen state only updates while something is watching it, as the screen does. */
    private fun kotlinx.coroutines.test.TestScope.watchState() {
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun theTextTheUserCheckedIsSentAsAMessage_andTheChatShowsAShortNote() = runTest {
        watchState()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SendPacket(PacketPhoto("Fry the chicken 5 minutes, add the seasoning and 3 tbsp water.", null), "Fajita Kit"))
        testDispatcher.scheduler.advanceUntilIdle()

        val last = sent.single().messages.last()
        assertTrue(last.content.startsWith("Here are the instructions from the packet of my Fajita Kit."))
        assertTrue(last.content.contains("3 tbsp water"))
        assertNull(last.imageJpegBase64)
        // The chat shows a short note, never the packet's text or the picture.
        val shown = chatDao.observeMessagesForConversation("default_conversation").first().last { it.role == "USER" }
        assertEquals("Here are the instructions from the packet of my Fajita Kit.\n(text from the packet sent)", shown.content)
    }

    @Test
    fun thePictureIsSentWhenTheUserLetsClaudeReadIt() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.SendPacket(PacketPhoto("", "QUJD"), null))
        testDispatcher.scheduler.advanceUntilIdle()

        val last = sent.single().messages.last()
        assertEquals("QUJD", last.imageJpegBase64)
        assertTrue(last.content.startsWith("Here is the packet."))
        val shown = chatDao.observeMessagesForConversation("default_conversation").first().last { it.role == "USER" }
        assertTrue(shown.content.endsWith("(photo of the packet sent for Claude to read)"))
    }

    @Test
    fun theScanScreenOpensForTheKitAndClosesAfterSending() = runTest {
        watchState()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.OpenPacketScan("Fajita Kit"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showPacketScan)
        assertEquals("Fajita Kit", viewModel.uiState.value.packetScanKit)

        viewModel.onEvent(ChatUiEvent.SendPacket(PacketPhoto("Fry for 5 minutes and add water and seasoning", null), "Fajita Kit"))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.showPacketScan)

        viewModel.onEvent(ChatUiEvent.OpenPacketScan(null))
        viewModel.onEvent(ChatUiEvent.ClosePacketScan)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.showPacketScan)
    }

    @Test
    fun withNeitherWordsNorAPacketNothingIsSent() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }
}
