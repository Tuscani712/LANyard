package io.github.tuscani712.lanyard.scan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR scanning on classic Android views (not Compose), so the preview, hint and
 * buttons are laid out by the view system and never overlap. Returns the decoded
 * `lanyard://pair?...` link as [EXTRA_LINK], or [EXTRA_PASTE] = true if the user
 * chose to paste instead.
 *
 * The camera permission is requested with a plain explanation; if it is denied
 * the screen still shows working Cancel / Paste buttons.
 */
class QrScanActivity : ComponentActivity() {
    private var previewView: PreviewView? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val answered = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun buildLayout(): LinearLayout {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0E14"))
            setPadding(pad, pad * 3, pad, pad) // top inset clears the status bar
        }
        root.addView(TextView(this).apply {
            text = "Point the camera at the QR code on the other device."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        previewView = preview
        root.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply {
            topMargin = pad
            bottomMargin = pad
        })

        root.addView(button("Paste a link instead") { finishPaste() }, squareParams())
        root.addView(button("Cancel") { finish() }, squareParams())
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun squareParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        bottomMargin = (resources.displayMetrics.density * 8).toInt()
    }

    private fun bindCamera() {
        val previewView = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { link ->
                if (answered.compareAndSet(false, true)) {
                    runOnUiThread {
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_LINK, link))
                        finish()
                    }
                }
            })
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // No camera available; the Paste/Cancel buttons still work.
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishPaste() {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_PASTE, true))
        finish()
    }

    override fun onDestroy() {
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        executor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            bindCamera()
        }
    }

    companion object {
        private const val REQUEST_CAMERA = 41
        const val EXTRA_LINK = "link"
        const val EXTRA_PASTE = "paste"
    }
}
