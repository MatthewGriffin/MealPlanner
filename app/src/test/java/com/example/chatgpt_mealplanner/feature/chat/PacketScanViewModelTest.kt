package com.example.chatgpt_mealplanner.feature.chat

import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PacketScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var results = ArrayDeque<String?>()
    private val uri: android.net.Uri = android.net.FakeUri()
    private lateinit var viewModel: PacketScanViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        viewModel = PacketScanViewModel(object : PacketPhotoSource {
            override suspend fun prepare(uri: android.net.Uri): String? = results.removeFirstOrNull()
        })
    }

    @Test
    fun aPhotoGoesToThePreview() = runTest {
        results.add("QUJD")

        viewModel.addPage(uri)
        assertEquals(PacketScanStage.READING, viewModel.state.value.stage)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(PacketScanStage.PREVIEW, viewModel.state.value.stage)
        assertEquals(listOf("QUJD"), viewModel.state.value.pages)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun furtherPagesAreAddedInOrder_andOneCanBeRemoved() = runTest {
        results.addAll(listOf("A", "B", "C"))
        repeat(3) { viewModel.addPage(uri); dispatcher.scheduler.advanceUntilIdle() }
        assertEquals(listOf("A", "B", "C"), viewModel.state.value.pages)

        viewModel.removePage(1)
        assertEquals(listOf("A", "C"), viewModel.state.value.pages)
        assertEquals(PacketScanStage.PREVIEW, viewModel.state.value.stage)

        viewModel.removePage(0)
        viewModel.removePage(0)
        assertEquals(PacketScanStage.CHOOSE, viewModel.state.value.stage)
    }

    @Test
    fun aPhotoThatCannotBeOpenedKeepsTheEarlierPagesAndShowsAMessage() = runTest {
        results.addAll(listOf("A", null))
        viewModel.addPage(uri); dispatcher.scheduler.advanceUntilIdle()
        viewModel.addPage(uri); dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("A"), viewModel.state.value.pages)
        assertEquals(PacketScanStage.PREVIEW, viewModel.state.value.stage)
        assertTrue(viewModel.state.value.error!!.contains("couldn't be opened"))
    }

    @Test
    fun aFirstPhotoThatCannotBeOpenedGoesBackToChoosing() = runTest {
        results.add(null)
        viewModel.addPage(uri); dispatcher.scheduler.advanceUntilIdle()

        assertEquals(PacketScanStage.CHOOSE, viewModel.state.value.stage)
    }

    @Test
    fun openingTheScreenAgainStartsFresh() = runTest {
        results.add("A")
        viewModel.addPage(uri); dispatcher.scheduler.advanceUntilIdle()

        viewModel.reset()
        assertEquals(PacketScanState(), viewModel.state.value)
    }
}
