package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.contract.AiProviderRequest
import com.example.chatgpt_mealplanner.contract.AiProviderResponse
import com.example.chatgpt_mealplanner.core.ai.context.AiContextBuilder
import com.example.chatgpt_mealplanner.core.ai.context.UserMutationIntentResolver
import com.example.chatgpt_mealplanner.core.ai.dispatcher.AiToolDispatcher
import com.example.chatgpt_mealplanner.core.ai.orchestrator.AiMealPlannerOrchestrator
import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto
import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhotoSource
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
    private var photoResult: PacketPhoto? = PacketPhoto("", null)
    private var photosRead = 0

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
        val photos = object : PacketPhotoSource {
            override suspend fun read(uri: android.net.Uri): PacketPhoto? {
                photosRead++
                return photoResult
            }
        }
        viewModel = ChatViewModel(orchestrator, chatRepository, mealService, packetPhotoSource = photos)
    }

    private val uri: android.net.Uri = android.net.FakeUri()

    /** The screen state only updates while something is watching it, as the screen does. */
    private fun kotlinx.coroutines.test.TestScope.watchState() {
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    @Test
    fun aPhotoWithGoodTextSendsTheTextAlongsideTheMessage_andShowsThatAPhotoWasSent() = runTest {
        photoResult = PacketPhoto("Fry the chicken for 5 minutes, add the seasoning and 3 tbsp water.", null)
        watchState()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.AttachPhoto(uri))
        viewModel.onEvent(ChatUiEvent.UpdateMessageText("this is my fajita kit"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testDispatcher.scheduler.advanceUntilIdle()

        val last = sent.single().messages.last()
        assertTrue(last.content.contains("this is my fajita kit"))
        assertTrue(last.content.contains("3 tbsp water"))
        assertNull(last.imageJpegBase64)
        // The chat shows a short note, not the packet's text, and never stores the picture.
        val shown = chatDao.observeMessagesForConversation("default_conversation").first().last { it.role == "USER" }
        assertEquals("this is my fajita kit\n(photo of the packet attached)", shown.content)
        assertNull(viewModel.uiState.value.attachedPhotoUri)
    }

    @Test
    fun aPhotoTheTextReaderCouldNotReadGoesToClaudeAsAPicture() = runTest {
        photoResult = PacketPhoto("FAJITA", "QUJD")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.AttachPhoto(uri))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testDispatcher.scheduler.advanceUntilIdle()

        val last = sent.single().messages.last()
        assertEquals("QUJD", last.imageJpegBase64)
        // A photo with no words is a complete message.
        assertTrue(last.content.startsWith("Here is the photo of the packet."))
    }

    @Test
    fun aPhotoThatCannotBeOpenedSendsNothing_andGivesTheWordsBack() = runTest {
        photoResult = null
        watchState()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.AttachPhoto(uri))
        viewModel.onEvent(ChatUiEvent.UpdateMessageText("my kit"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(sent.isEmpty())
        assertEquals("my kit", viewModel.uiState.value.userMessageText)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("couldn't be read"))
        assertEquals(ChatRequestState.IDLE, viewModel.uiState.value.requestState)
    }

    @Test
    fun removingThePhotoMeansItIsNotSent() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onEvent(ChatUiEvent.AttachPhoto(uri))
        viewModel.onEvent(ChatUiEvent.RemovePhoto)
        viewModel.onEvent(ChatUiEvent.UpdateMessageText("just words"))
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, photosRead)
        assertEquals("just words", sent.single().messages.last().content)
    }

    @Test
    fun withNeitherWordsNorAPhotoNothingIsSent() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onEvent(ChatUiEvent.SendMessage)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }
}
