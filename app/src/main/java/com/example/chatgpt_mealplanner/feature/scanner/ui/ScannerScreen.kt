package com.example.chatgpt_mealplanner.feature.scanner.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.chatgpt_mealplanner.feature.scanner.ScannerState
import com.example.chatgpt_mealplanner.feature.scanner.ScannerUiEvent
import com.example.chatgpt_mealplanner.feature.scanner.analyzer.BarcodeAnalyzer
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    state: ScannerState,
    onEvent: (ScannerUiEvent) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state) {
        if (state is ScannerState.Saved) {
            onEvent(ScannerUiEvent.ScanAnother)
            onNavigateBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (state) {
                ScannerState.RequestingPermission, ScannerState.PermissionDenied -> {
                    CameraPermissionHandler(
                        onPermissionGranted = { onEvent(ScannerUiEvent.PermissionGranted) },
                        onPermissionDenied = { onEvent(ScannerUiEvent.PermissionDenied) },
                        onManualAddClicked = { onEvent(ScannerUiEvent.ManualAddRequested) }
                    )
                }

                ScannerState.Ready, ScannerState.Scanning, is ScannerState.LookingUp -> {
                    CameraXScannerView(
                        onBarcodeScanned = { barcode ->
                            onEvent(ScannerUiEvent.BarcodeScanned(barcode))
                        }
                    )

                    // Target box overlay
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .align(Alignment.Center)
                            .border(3.dp, Color.White, RoundedCornerShape(16.dp))
                    )

                    if (state is ScannerState.LookingUp) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Looking up barcode...", color = Color.White)
                            }
                        }
                    } else {
                        Text(
                            text = "Point camera at grocery barcode",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 60.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                is ScannerState.Confirming -> {
                    ProductConfirmationScreen(
                        barcode = state.barcode,
                        metadata = state.editableMetadata,
                        onBack = { onEvent(ScannerUiEvent.ScanAnother) },
                        onConfirm = { confirmedInput ->
                            onEvent(ScannerUiEvent.ConfirmProduct(confirmedInput))
                        }
                    )
                }

                is ScannerState.NotFound -> {
                    ResultBanner(
                        title = "Product Not Found",
                        message = "Barcode ${state.barcode} wasn't found online. Add product details manually below.",
                        primaryButtonText = "Add Details Manually",
                        onPrimaryClick = { onEvent(ScannerUiEvent.ManualAddRequested) },
                        secondaryButtonText = "Scan Another",
                        onSecondaryClick = { onEvent(ScannerUiEvent.ScanAnother) }
                    )
                }

                is ScannerState.Error -> {
                    ResultBanner(
                        title = "Lookup Error",
                        message = state.message,
                        primaryButtonText = "Add Manually",
                        onPrimaryClick = { onEvent(ScannerUiEvent.ManualAddRequested) },
                        secondaryButtonText = "Try Again",
                        onSecondaryClick = { onEvent(ScannerUiEvent.ScanAnother) }
                    )
                }

                is ScannerState.Saving -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                is ScannerState.Saved -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                else -> {}
            }
        }
    }
}

@Composable
fun CameraXScannerView(
    onBarcodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    BarcodeAnalyzer(onBarcodeScanned)
                )

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun ResultBanner(
    title: String,
    message: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onPrimaryClick, modifier = Modifier.fillMaxWidth()) {
                    Text(primaryButtonText)
                }
                if (secondaryButtonText != null && onSecondaryClick != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onSecondaryClick, modifier = Modifier.fillMaxWidth()) {
                        Text(secondaryButtonText)
                    }
                }
            }
        }
    }
}
