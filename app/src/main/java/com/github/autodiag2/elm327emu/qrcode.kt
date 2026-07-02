package com.github.autodiag2.elm327emu

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

fun generateQrBitmap(text: String, size: Int = 800): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    for (y in 0 until size) {
        for (x in 0 until size) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) Color.BLACK else Color.WHITE
            )
        }
    }

    return bitmap
}