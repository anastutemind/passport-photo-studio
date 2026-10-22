package com.aps.passportphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.round

object ImageUtils {

    const val DPI = 300f

    /** Convert millimetres to pixels at print DPI. */
    fun mmToPx(mm: Float): Int = round(mm / 25.4f * DPI).toInt()

    /** Decode a picked image into a software ARGB_8888 bitmap, capped to [maxDim] on the long side. */
    fun decodeBitmap(context: Context, uri: Uri, maxDim: Int = 2200): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxDim) {
                val scale = maxDim.toFloat() / longest
                decoder.setTargetSize(round(w * scale).toInt(), round(h * scale).toInt())
            }
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true) }
    }

    /**
     * Brightness (multiplier), contrast (multiplier around mid-grey) and saturation color matrix,
     * matching the behaviour of CSS brightness()/contrast()/saturate().
     */
    fun colorMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(saturation)
        val t = 127.5f * (1f - contrast)
        val contrastM = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val brightM = ColorMatrix(
            floatArrayOf(
                brightness, 0f, 0f, 0f, 0f,
                0f, brightness, 0f, 0f, 0f,
                0f, 0f, brightness, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastM)
        cm.postConcat(brightM)
        return cm
    }

    /** Cheap blur by downscaling then upscaling with bilinear filtering. */
    fun cheapBlur(src: Bitmap, factor: Int): Bitmap {
        if (factor <= 1) return src
        val w = max(1, src.width / factor)
        val h = max(1, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val back = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small != back) small.recycle()
        return back
    }

    /**
     * Soften only skin-coloured pixels (YCbCr skin test), leaving eyes/hair/edges sharp.
     * [amount] is 0..100.
     */
    fun applySkinBlur(bmp: Bitmap, amount: Float) {
        if (amount <= 0f) return
        val factor = (2 + amount / 25f).toInt().coerceIn(2, 8)
        val blurred = cheapBlur(bmp, factor)
        val w = bmp.width
        val h = bmp.height
        val base = IntArray(w * h)
        val blur = IntArray(w * h)
        bmp.getPixels(base, 0, w, 0, 0, w, h)
        blurred.getPixels(blur, 0, w, 0, 0, w, h)
        if (blurred != bmp) blurred.recycle()
        val strength = (amount / 100f) * 0.85f
        for (i in base.indices) {
            val p = base[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            val isSkin = cb in 77f..135f && cr in 133f..180f && y > 40f
            if (isSkin) {
                val q = blur[i]
                val br = (q shr 16) and 0xFF
                val bg = (q shr 8) and 0xFF
                val bb = q and 0xFF
                val nr = (r + (br - r) * strength).toInt()
                val ng = (g + (bg - g) * strength).toInt()
                val nb = (b + (bb - b) * strength).toInt()
                base[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bmp.setPixels(base, 0, w, 0, 0, w, h)
    }
}
