package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhotoSource
import com.example.chatgpt_mealplanner.core.ai.packet.PacketScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PacketScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var result: PacketScan? = null
    private val uri: android.net.Uri = android.net.FakeUri()
    private lateinit var viewModel: PacketScanViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel = PacketScanViewModel(object : PacketPhotoSource {
            override suspend fun scan(uri: android.net.Uri): PacketScan? = result
        })
    }

    @Test
    fun aReadablePhotoGoesToThePreviewWithTheTextToCheck() = runTest {
        result = PacketScan("Fry for 5 minutes. Add 3 tbsp water.", 0.9f, 0.8f, "QUJD")

        viewModel.scan(uri)
        assertEquals(PacketScanStage.READING, viewModel.state.value.stage)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PacketScanStage.PREVIEW, state.stage)
        assertEquals("Fry for 5 minutes. Add 3 tbsp water.", state.checkedText)
        assertNotNull(state.scan)
        assertNull(state.error)
    }

    @Test
    fun theUserCanCorrectTheTextBeforeSendingIt() = runTest {
        result = PacketScan("Add 3 tbsp watr", 0.9f, 0.8f, "QUJD")
        viewModel.scan(uri)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.editText("Add 3 tbsp water")

        assertEquals("Add 3 tbsp water", viewModel.state.value.checkedText)
        // What the phone read is kept, so retaking or comparing is still possible.
        assertEquals("Add 3 tbsp watr", viewModel.state.value.scan!!.text)
    }

    @Test
    fun aPhotoThePhoneCouldNotReadStillShowsThePreviewWithAWarningAndThePicture() = runTest {
        result = PacketScan("", null, null, "QUJD")

        viewModel.scan(uri)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PacketScanStage.PREVIEW, state.stage)
        assertTrue(!state.scan!!.looksGood)
        assertNotNull(state.scan!!.pictureForClaude())
    }

    @Test
    fun aPhotoThatCannotBeOpenedGoesBackToChoosingWithAMessage() = runTest {
        result = null

        viewModel.scan(uri)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(PacketScanStage.CHOOSE, viewModel.state.value.stage)
        assertTrue(viewModel.state.value.error!!.contains("couldn't be opened"))
    }

    @Test
    fun retakingAndOpeningTheScreenAgainStartFresh() = runTest {
        result = PacketScan("Add water and heat for 5 minutes", 0.9f, 0.8f, "QUJD")
        viewModel.scan(uri)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.retake()
        assertEquals(PacketScanStage.CHOOSE, viewModel.state.value.stage)
        assertNull(viewModel.state.value.scan)

        viewModel.scan(uri)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()
        assertEquals(PacketScanState(), viewModel.state.value)
    }
}
