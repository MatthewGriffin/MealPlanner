package com.example.chatgpt_mealplanner.feature.chat.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/** Opens the camera or the gallery for a photo of a kit's packet. */
class PacketPhotoPicker(val takePhoto: () -> Unit, val chooseFromGallery: () -> Unit)

@Composable
fun rememberPacketPhotoPicker(onPhoto: (Uri) -> Unit, onProblem: (String) -> Unit): PacketPhotoPicker {
    val context = LocalContext.current
    val photo by rememberUpdatedState(onPhoto)
    val problem by rememberUpdatedState(onProblem)
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri
        if (success && uri != null) photo(uri)
        else {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            if (!success) problem("No photo was taken.")
        }
    }

    val launchCamera: () -> Unit = {
        try {
            // Packet photos left behind if the app was closed before they were sent.
            context.cacheDir.listFiles { file -> file.name.startsWith("packet_") && file.name.endsWith(".jpg") }?.forEach { it.delete() }
            val file = File.createTempFile("packet_", ".jpg", context.cacheDir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingUri = uri
            cameraLauncher.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            problem("No camera app is available on this device.")
        } catch (e: Exception) {
            problem("Couldn't open the camera: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else problem("Camera permission is needed to take a photo. You can choose one from your gallery instead.")
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { photo(it) }
    }

    return PacketPhotoPicker(
        takePhoto = {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        chooseFromGallery = { galleryLauncher.launch("image/*") }
    )
}
