package io.github.tuscani712.lanyard.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The QR path round-trips: encode a real pairing link with ZXing, render it to a
 * luminance image, and decode it back. This is the same library the camera
 * scanner uses, so it proves the encode/decode pair without a camera.
 */
class QrRoundTripTest {
    @Test
    fun encodesAndDecodesAPairingLink() {
        val link = PairLink.build(
            PairLink.Payload(
                fingerprint = "ab".repeat(32),
                name = "desktop",
                addrs = listOf("192.168.90.121:47800"),
                nonce = "cd".repeat(16),
            ),
        )

        val size = 512
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size)

        // BitMatrix -> ARGB pixels, black module = 0xFF000000.
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }

        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val decoded = MultiFormatReader().apply { setHints(hints) }.decode(bitmap)

        assertEquals(link, decoded.text)
        // And the decoded text is a valid pairing link carrying the same identity.
        assertEquals("ab".repeat(32), PairLink.parse(decoded.text).fingerprint)
    }
}
