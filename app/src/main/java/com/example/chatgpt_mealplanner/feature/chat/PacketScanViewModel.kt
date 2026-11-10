package com.example.chatgpt_mealplanner.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhotoSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PacketScanStage { CHOOSE, READING, PREVIEW }

data class PacketScanState(
    val stage: PacketScanStage = PacketScanStage.CHOOSE,
    /** The photos taken so far, each as a JPEG in base64, in order. A recipe can run over several pages. */
    val pages: List<String> = emptyList(),
    val error: String? = null
)

/** The scan screen: the user takes one or more photos, looks at them, and sends them for Claude to read. */
@HiltViewModel
class PacketScanViewModel @Inject constructor(
    private val source: PacketPhotoSource,
    @param:ApplicationContext private val context: Context? = null
) : ViewModel() {

    private val _state = MutableStateFlow(PacketScanState())
    val state: StateFlow<PacketScanState> = _state.asStateFlow()

    private var readingJob: kotlinx.coroutines.Job? = null

    fun reset() {
        readingJob?.cancel()
        _state.value = PacketScanState()
    }

    /** Adds a photo to the pages. */
    fun addPage(uri: Uri) {
        _state.update { it.copy(stage = PacketScanStage.READING, error = null) }
        readingJob = viewModelScope.launch {
            val page = try {
                source.prepare(uri)
            } finally {
                deleteOwnTempPhoto(uri)
            }
            _state.update { current ->
                if (page == null) {
                    current.copy(
                        stage = if (current.pages.isEmpty()) PacketScanStage.CHOOSE else PacketScanStage.PREVIEW,
                        error = "That photo couldn't be opened. Try taking it again, or choose another."
                    )
                } else {
                    current.copy(stage = PacketScanStage.PREVIEW, pages = current.pages + page, error = null)
                }
            }
        }
    }

    /** Gives up on the photo being prepared and goes back to what was there before. */
    fun cancelReading() {
        readingJob?.cancel()
        _state.update { it.copy(stage = if (it.pages.isEmpty()) PacketScanStage.CHOOSE else PacketScanStage.PREVIEW) }
    }

    fun removePage(index: Int) {
        _state.update { current ->
            val pages = current.pages.filterIndexed { i, _ -> i != index }
            current.copy(pages = pages, stage = if (pages.isEmpty()) PacketScanStage.CHOOSE else PacketScanStage.PREVIEW)
        }
    }

    fun problem(message: String) {
        _state.update { it.copy(error = message) }
    }

    /** The camera's photo is only needed while it is prepared, so it isn't left in the cache. Gallery pictures are never deleted. */
    private fun deleteOwnTempPhoto(uri: Uri) {
        val ctx = context ?: return
        if (uri.authority == "${ctx.packageName}.fileprovider") {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
    }
}
