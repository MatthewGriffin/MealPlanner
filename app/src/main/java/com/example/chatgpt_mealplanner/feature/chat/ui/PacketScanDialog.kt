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
 * Scan a kit's packet. The phone turns the photo into text; the user sees that text beside the picture and can
 * correct it. If it looks wrong or the phone couldn't read it, they let Claude read the picture instead.
 */
@Composable
fun PacketScanDialog(
    kitName: String?,
    onSend: (PacketPhoto) -> Unit,
    onCancel: () -> Unit,
    viewModel: PacketScanViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.reset() }
    val state by viewModel.state.collectAsState()
    val picker = rememberPacketPhotoPicker(onPhoto = viewModel::scan, onProblem = viewModel::problem)

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
                        Text("Scan the packet", style = MaterialTheme.typography.titleLarge)
                        if (!kitName.isNullOrBlank()) Text("for $kitName", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Spacer(Modifier.height(8.dp))
                when (state.stage) {
                    PacketScanStage.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Photograph the back of the packet where the cooking instructions are. Your phone reads the text, and you can check it before anything is sent.",
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
                        Text("Reading the text on your phone…")
                    }
                    PacketScanStage.PREVIEW -> {
                        val scan = state.scan!!
                        val picture = remember(scan.jpegBase64) {
                            scan.jpegBase64?.let {
                                val bytes = Base64.decode(it, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
                            }
                        }
                        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (scan.looksGood) {
                                Text("The phone read this. Check it against the packet and correct anything that's wrong.", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("The phone's reading may not be good enough", style = MaterialTheme.typography.titleSmall)
                                        scan.problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                        if (picture != null) Text("Let Claude read the photo instead, or retake it.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            picture?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Your photo of the packet",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth().height(160.dp)
                                )
                            }
                            OutlinedTextField(
                                value = state.checkedText,
                                onValueChange = viewModel::editText,
                                label = { Text("Text read from the packet") },
                                minLines = 6,
                                modifier = Modifier.fillMaxWidth().testTag("scan-text")
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val canSendPicture = scan.pictureForClaude() != null
                        val canSendText = state.checkedText.isNotBlank()
                        if (scan.looksGood) {
                            Button(onClick = { onSend(scan.textForClaude(state.checkedText)) }, enabled = canSendText, modifier = Modifier.fillMaxWidth().testTag("scan-send-text")) {
                                Text("Send this text to Claude")
                            }
                            if (canSendPicture) OutlinedButton(onClick = { onSend(scan.pictureForClaude()!!) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Let Claude read the photo instead")
                            }
                        } else {
                            if (canSendPicture) Button(onClick = { onSend(scan.pictureForClaude()!!) }, modifier = Modifier.fillMaxWidth().testTag("scan-send-picture")) {
                                Text("Let Claude read the photo")
                            }
                            OutlinedButton(onClick = { onSend(scan.textForClaude(state.checkedText)) }, enabled = canSendText, modifier = Modifier.fillMaxWidth()) {
                                Text("Send this text anyway")
                            }
                        }
                        TextButton(onClick = viewModel::retake, modifier = Modifier.fillMaxWidth()) { Text("Retake photo") }
                    }
                }
            }
        }
    }
}
