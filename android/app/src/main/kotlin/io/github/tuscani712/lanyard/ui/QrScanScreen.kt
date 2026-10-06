package io.github.tuscani712.lanyard.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.tuscani712.lanyard.scan.QrAnalyzer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A QR scan screen: camera preview plus ZXing analysis. It asks for the camera
 * permission with a plain-words explanation, shows a denied state with a
 * paste-a-link escape hatch, and releases the camera both when it leaves
 * composition and when the app is backgrounded (CameraX is bound to the
 * lifecycle owner).
 */
@Composable
fun QrScanScreen(onDecoded: (String) -> Unit, onCancel: () -> Unit, onPasteInstead: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }

    when {
        granted -> CameraPreview(onDecoded = onDecoded, onCancel = onCancel)
        !asked -> PermissionIntro(
            onAllow = { launcher.launch(Manifest.permission.CAMERA) },
            onCancel = onCancel,
        )
        else -> PermissionDenied(onPasteInstead = onPasteInstead, onCancel = onCancel)
    }
}

@Composable
private fun PermissionIntro(onAllow: () -> Unit, onCancel: () -> Unit) {
    CenteredNotice(
        title = "Scan a pairing code",
        body = "LANyard needs the camera to scan the QR code the other device shows. " +
            "The camera is only used while this screen is open.",
        primaryLabel = "Allow camera",
        onPrimary = onAllow,
        secondaryLabel = "Cancel",
        onSecondary = onCancel,
    )
}

@Composable
private fun PermissionDenied(onPasteInstead: () -> Unit, onCancel: () -> Unit) {
    CenteredNotice(
        title = "Camera permission denied",
        body = "You can still pair by pasting a link: enable the camera in Settings, or add the device manually.",
        primaryLabel = "Paste a link instead",
        onPrimary = onPasteInstead,
        secondaryLabel = "Back",
        onSecondary = onCancel,
    )
}

@Composable
private fun CenteredNotice(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPrimary) { Text(primaryLabel) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
    }
}

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE uses a TextureView so the Compose overlay draws above the
            // preview; a SurfaceView would cover it.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val answered = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { link ->
                if (answered.compareAndSet(false, true)) previewView.post { onDecoded(link) }
            })
            try {
                provider.unbindAll()
                // Bound to the lifecycle owner: frames stop when the app is backgrounded.
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // No camera available (e.g. some emulators); the cancel button still works.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Point the camera at the other device's QR code.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)) {
            Text("Cancel")
        }
    }
}
