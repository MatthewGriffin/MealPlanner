package com.example.chatgpt_mealplanner.feature.chat.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.chatgpt_mealplanner.core.ai.packet.PacketPhoto
import com.example.chatgpt_mealplanner.feature.chat.PacketScanStage
import com.example.chatgpt_mealplanner.feature.chat.PacketScanViewModel

/**
 * Photograph a kit's packet or a recipe. The photos go to Claude as they are; a recipe can take several pages,
 * added one at a time, and the user sees them before sending.
 */
@Composable
fun PacketScanDialog(
    kitName: String?,
    recipe: Boolean = false,
    onSend: (PacketPhoto) -> Unit,
    onCancel: () -> Unit,
    viewModel: PacketScanViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.reset() }
    val state by viewModel.state.collectAsState()
    val picker = rememberPacketPhotoPicker(onPhoto = viewModel::addPage, onProblem = viewModel::problem)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (recipe) "Scan a recipe" else "Scan the packet", style = MaterialTheme.typography.titleLarge)
                        if (!kitName.isNullOrBlank()) Text("for $kitName", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Spacer(Modifier.height(8.dp))
                when (state.stage) {
                    PacketScanStage.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (recipe) "Photograph the recipe with the ingredients and method in view. If it runs over several pages, you can add them one at a time before sending."
                            else "Photograph the back of the packet where the cooking instructions are.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Button(onClick = picker.takePhoto, modifier = Modifier.fillMaxWidth().testTag("scan-take-photo")) { Text("Take a photo") }
                        OutlinedButton(onClick = picker.chooseFromGallery, modifier = Modifier.fillMaxWidth()) { Text("Choose from gallery") }
                    }
                    PacketScanStage.READING -> Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Getting the photo ready…")
                        TextButton(onClick = viewModel::cancelReading, modifier = Modifier.testTag("scan-cancel-reading")) { Text("Cancel") }
                    }
                    PacketScanStage.PREVIEW -> {
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (state.pages.size == 1) "Check the photo is clear and shows everything, then send it to Claude."
                                else "Check each page is clear. Claude reads them in this order.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                            state.pages.forEachIndexed { index, page ->
                                val bitmap = remember(page) {
                                    val bytes = Base64.decode(page, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (state.pages.size > 1) "Page ${index + 1}" else "Photo",
                                            style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { viewModel.removePage(index) }) { Text("Remove") }
                                    }
                                    bitmap?.let {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = "Photo ${index + 1}",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxWidth().height(220.dp)
                                        )
                                    }
                                }
                            }
                            if (state.pages.size < MAX_PAGES) {
                                Text(if (recipe) "Is there more of the recipe?" else "Is there more to the instructions?", style = MaterialTheme.typography.labelLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = picker.takePhoto, modifier = Modifier.weight(1f)) { Text("Add: take photo") }
                                    OutlinedButton(onClick = picker.chooseFromGallery, modifier = Modifier.weight(1f)) { Text("Add: gallery") }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { onSend(PacketPhoto(state.pages)) },
                            enabled = state.pages.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().testTag("scan-send-picture")
                        ) { Text(if (state.pages.size > 1) "Send ${state.pages.size} pages to Claude" else "Send to Claude") }
                    }
                }
            }
        }
    }
}

private const val MAX_PAGES = 6
