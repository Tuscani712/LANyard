package io.github.tuscani712.lanyard.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes QR codes from camera frames with ZXing core (no Play Services). Only a
 * `lanyard://pair?...` link is reported, and only once. The link is never logged.
 */
class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    @Volatile
    private var handled = false

    override fun analyze(image: ImageProxy) {
        if (handled) {
            image.close()
            return
        }
        try {
            val plan = image.planes[0]
            val buffer = plan.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val source = PlanarYUVLuminanceSource(
                data, plan.rowStride, image.height, 0, 0, plan.rowStride, image.height, false,
            )
            val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            if (text != null && text.startsWith("lanyard://pair")) {
                handled = true
                onDecoded(text)
            }
        } catch (_: NotFoundException) {
            // no code in this frame
        } catch (_: Exception) {
            // ignore malformed frames
        } finally {
            reader.reset()
            image.close()
        }
    }
}
