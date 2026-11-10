package com.example.chatgpt_mealplanner.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhotoSource
import com.example.chatgpt_mealplanner.core.ai.packet.PacketScan
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
    val scan: PacketScan? = null,
    /** What the phone read, which the user can correct before it is sent. */
    val checkedText: String = "",
    val error: String? = null
)

/** The Scan packet screen: the phone reads a photo, and the user checks the text before choosing what Claude gets. */
@HiltViewModel
class PacketScanViewModel @Inject constructor(
    private val source: PacketPhotoSource,
    @param:ApplicationContext private val context: Context? = null
) : ViewModel() {

    private val _state = MutableStateFlow(PacketScanState())
    val state: StateFlow<PacketScanState> = _state.asStateFlow()

    fun reset() {
        _state.value = PacketScanState()
    }

    /** [instructionsOnly] cuts the text down to cooking steps, for a kit's packet; a recipe needs all of it. */
    fun scan(uri: Uri, instructionsOnly: Boolean = true) {
        _state.update { it.copy(stage = PacketScanStage.READING, error = null) }
        viewModelScope.launch {
            val scan = try {
                source.scan(uri)
            } finally {
                deleteOwnTempPhoto(uri)
            }
            _state.value = if (scan == null) {
                PacketScanState(error = "That photo couldn't be opened. Try taking it again, or choose another.")
            } else {
                val shown = if (instructionsOnly) scan.copy(text = com.example.chatgpt_mealplanner.core.ai.packet.PacketTextFilter.keepInstructions(scan.text)) else scan
                PacketScanState(stage = PacketScanStage.PREVIEW, scan = shown, checkedText = shown.text)
            }
        }
    }

    fun editText(text: String) {
        _state.update { it.copy(checkedText = text) }
    }

    fun retake() {
        _state.value = PacketScanState()
    }

    fun problem(message: String) {
        _state.update { it.copy(error = message) }
    }

    /** The camera's photo is only needed while it is read, so it isn't left in the cache. Gallery pictures are never deleted. */
    private fun deleteOwnTempPhoto(uri: Uri) {
        val ctx = context ?: return
        if (uri.authority == "${ctx.packageName}.fileprovider") {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        }
    }
}
