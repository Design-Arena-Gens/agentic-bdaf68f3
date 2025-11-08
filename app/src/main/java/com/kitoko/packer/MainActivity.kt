package com.kitoko.packer

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.kitoko.packer.data.PackedOrderStore
import com.kitoko.packer.ui.screens.KitokoPackerRoot
import com.kitoko.packer.ui.state.PackingViewModel
import com.kitoko.packer.ui.theme.KitokoPackerTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: PackingViewModel by viewModels {
        PackingViewModel.factory(
            PackedOrderStore(applicationContext),
            FirebaseAuth.getInstance(),
            FirebaseFirestore.getInstance()
        )
    }

    private lateinit var tts: TextToSpeech
    private var ttsReady: Boolean = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCameraPermission()
        setupTextToSpeech()

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val snackbarHostState = SnackbarHostState()

            LaunchedEffect(state.snackbarMessage) {
                val message = state.snackbarMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(
                    message = message.text,
                    duration = SnackbarDuration.Short
                )
                viewModel.consumeMessage()
            }

            LaunchedEffect(state.overlayState?.speakMessage) {
                val speak = state.overlayState?.speakMessage ?: return@LaunchedEffect
                if (ttsReady) {
                    tts.speak(speak, TextToSpeech.QUEUE_FLUSH, null, speak)
                }
            }

            KitokoPackerTheme {
                KitokoPackerRoot(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onSignIn = viewModel::signIn,
                    onSignOut = viewModel::signOut,
                    onBarcodeScanned = viewModel::handleBarcode,
                    onOverlayDismissed = viewModel::clearOverlay,
                    onExportCsv = { exportCsv() },
                    onClearHistory = viewModel::clearHistory,
                    onRequestCameraPermission = { requestCameraPermission() }
                )
            }
        }
    }

    private fun setupTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts.language = Locale.US
            }
        }
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun exportCsv() {
        ensureStoragePermission()
        lifecycleScope.launch {
            val csv = viewModel.buildCsv()
            if (csv.isBlank()) {
                Toast.makeText(this@MainActivity, "No data to export.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val fileName = "kitoko_packer_${System.currentTimeMillis()}.csv"
            val uri = writeCsv(fileName, csv)
            if (uri != null) {
                Toast.makeText(this@MainActivity, "Exported to Downloads.", Toast.LENGTH_SHORT).show()
                shareCsv(uri)
            } else {
                Toast.makeText(this@MainActivity, "Export failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeCsv(fileName: String, content: String): Uri? {
        return try {
            val resolver = contentResolver
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists()) downloads.mkdirs()
                val file = File(downloads, fileName)
                FileOutputStream(file).use { it.write(content.toByteArray()) }
                Uri.fromFile(file)
            }

            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray())
                }
            }
            uri
        } catch (error: Exception) {
            null
        }
    }

    private fun shareCsv(uri: Uri) {
        if (uri.scheme == "file") {
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share scan report"))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}
