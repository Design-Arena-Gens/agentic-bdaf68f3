package com.kitoko.packer.ui.screens

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.common.InputImage
import com.kitoko.packer.model.InvoiceLine
import com.kitoko.packer.model.OrderProgress
import com.kitoko.packer.model.PackedOrder
import com.kitoko.packer.ui.state.AuthState
import com.kitoko.packer.ui.state.PackedOverlayState
import com.kitoko.packer.ui.state.PackingUiState
import java.util.concurrent.Executors
import kotlin.math.abs

@Composable
fun KitokoPackerRoot(
    state: PackingUiState,
    snackbarHostState: SnackbarHostState,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onBarcodeScanned: (String) -> Unit,
    onOverlayDismissed: () -> Unit,
    onExportCsv: () -> Unit,
    onClearHistory: () -> Unit,
    onRequestCameraPermission: () -> Unit
) {
    if (!state.isSignedIn) {
        AuthScreen(
            isLoading = state.authState is AuthState.Loading,
            snackbarHostState = snackbarHostState,
            onSignIn = onSignIn
        )
    } else {
        PackingScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onBarcodeScanned = onBarcodeScanned,
            onOverlayDismissed = onOverlayDismissed,
            onSignOut = onSignOut,
            onExportCsv = onExportCsv,
            onClearHistory = onClearHistory,
            onRequestCameraPermission = onRequestCameraPermission
        )
    }
}

@Composable
private fun AuthScreen(
    isLoading: Boolean,
    snackbarHostState: SnackbarHostState,
    onSignIn: (String, String) -> Unit
) {
    val email = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Kitoko Packer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = email.value,
                onValueChange = { email.value = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = password.value,
                onValueChange = { password.value = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onSignIn(email.value.trim(), password.value.trim()) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isLoading) "Signing In…" else "Sign In")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackingScreen(
    state: PackingUiState,
    snackbarHostState: SnackbarHostState,
    onBarcodeScanned: (String) -> Unit,
    onOverlayDismissed: () -> Unit,
    onSignOut: () -> Unit,
    onExportCsv: () -> Unit,
    onClearHistory: () -> Unit,
    onRequestCameraPermission: () -> Unit
) {
    val appBarState = rememberTopAppBarState()
    val currentOrder = state.currentOrder

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kitoko Packer") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(appBarState),
                actions = {
                    TextButton(onClick = onSignOut) {
                        Text("Sign out", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FilledTonalButton(onClick = onExportCsv) {
                Text("Export CSV")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            CameraScanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .padding(16.dp),
                onBarcodeScanned = onBarcodeScanned,
                onRequestCameraPermission = onRequestCameraPermission
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (currentOrder == null) {
                EmptyState()
            } else {
                OrderSummary(order = currentOrder)
            }

            Spacer(modifier = Modifier.height(16.dp))

            HistoryList(
                history = state.history.take(10),
                onClearHistory = onClearHistory
            )
        }

        AnimatedVisibility(
            visible = state.overlayState != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            state.overlayState?.let { overlay ->
                PackedOverlay(overlay = overlay, onDismiss = onOverlayDismissed)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scan invoice QR to begin.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Then scan packet QR codes or SKUs to complete the checklist.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun OrderSummary(
    order: OrderProgress
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Order ${order.order.orderId}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(order.order.lines) { line ->
                OrderLineItem(
                    line = line,
                    packed = order.scanned[line.sku] ?: 0
                )
            }
        }
    }
}

@Composable
private fun OrderLineItem(
    line: InvoiceLine,
    packed: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = line.sku,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Packed: $packed")
                Text("Target: ${line.quantity}")
            }
        }
    }
}

@Composable
private fun HistoryList(
    history: List<PackedOrder>,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent packed orders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onClearHistory) {
                Text("Clear")
            }
        }
        if (history.isEmpty()) {
            Text(
                text = "No orders packed yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 240.dp)
            ) {
                items(history) { order ->
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = order.orderId,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = order.formattedTimestamp,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                text = order.items.joinToString { "${it.sku}×${it.quantity}" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackedOverlay(
    overlay: PackedOverlayState,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = overlay.message,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onDismiss) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun CameraScanner(
    modifier: Modifier = Modifier,
    onBarcodeScanned: (String) -> Unit,
    onRequestCameraPermission: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraController = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.PREVIEW)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisTargetSize = CameraController.OutputSize(Size(1280, 720))
        }
    }
    val scanner = remember {
        BarcodeScanning.getClient(
            com.google.mlkit.vision.barcode.BarcodeScannerOptions.Builder()
                .setBarcodeFormats(FORMAT_QR_CODE)
                .build()
        )
    }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val latestScan = remember { mutableStateOf("" to 0L) }
    val throttledCallback = rememberUpdatedState(onBarcodeScanned)

    DisposableEffect(lifecycleOwner) {
        cameraController.bindToLifecycle(lifecycleOwner)
        cameraController.setImageAnalysisAnalyzer(analyzerExecutor) { imageProxy ->
            processImage(
                imageProxy = imageProxy,
                scanner = scanner,
                latestValue = latestScan,
                onScanned = { throttledCallback.value(it) }
            )
        }
        onRequestCameraPermission()
        onDispose {
            cameraController.unbind()
            analyzerExecutor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            androidx.camera.view.PreviewView(ctx).apply {
                this.controller = cameraController
            }
        }
    )
}

private fun processImage(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    latestValue: MutableState<Pair<String, Long>>,
    onScanned: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(
        mediaImage,
        imageProxy.imageInfo.rotationDegrees
    )
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val qr = barcodes.firstOrNull { it.rawValue != null }?.rawValue
            if (qr != null) {
                val now = System.currentTimeMillis()
                val (lastValue, lastTime) = latestValue.value
                if (qr != lastValue || abs(now - lastTime) > 1200) {
                    latestValue.value = qr to now
                    onScanned(qr)
                }
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
