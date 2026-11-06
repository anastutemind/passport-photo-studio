package com.aps.passportphoto

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device alpha matting with MODNet (MobileNetV2 backbone, Apache-2.0), run through ONNX Runtime.
 * The ~26 MB fp32 model is downloaded once on first use and cached in the app's files dir, so the
 * APK itself stays small. Produces a SOFT alpha matte (0..255) that keeps individual hair strands,
 * which is the whole point over a hard segmentation mask.
 *
 * Everything is defensive: any failure (no network on first run, download error, runtime error)
 * returns null so the caller can fall back to ML Kit segmentation. Call off the main thread.
 */
object MattingModel {

    private const val MODEL_URL = "https://huggingface.co/Xenova/modnet/resolve/main/onnx/model.onnx?download=true"
    private const val MODEL_FILE = "modnet.onnx"
    private const val MIN_VALID_BYTES = 20_000_000L   // fp32 model is ~26 MB; reject a truncated download

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null

    /** True once the model file is present locally (so the caller can skip the "downloading" message). */
    fun isModelCached(ctx: Context): Boolean {
        val f = File(ctx.filesDir, MODEL_FILE)
        return f.exists() && f.length() >= MIN_VALID_BYTES
    }

    /** Download the model if needed. Returns the file, or null on failure. */
    private fun ensureModel(ctx: Context): File? {
        val f = File(ctx.filesDir, MODEL_FILE)
        if (f.exists() && f.length() >= MIN_VALID_BYTES) return f
        if (f.exists()) f.delete()
        val tmp = File(ctx.filesDir, "$MODEL_FILE.part")
        return try {
            var url = URL(MODEL_URL)
            var conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            // Follow a cross-protocol/host redirect manually if the platform declines to.
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                url = URL(url, loc)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
                }
                redirects++
            }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            conn.disconnect()
            if (tmp.length() < MIN_VALID_BYTES) { tmp.delete(); null }
            else if (tmp.renameTo(f)) f else null
        } catch (e: Exception) {
            tmp.delete(); null
        }
    }

    private fun ensureSession(ctx: Context): OrtSession? {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val file = ensureModel(ctx) ?: return null
            return try {
                val e = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                val s = e.createSession(file.absolutePath, opts)
                env = e; session = s; s
            } catch (e: Throwable) { null }
        }
    }

    /**
     * Returns an ARGB_8888 bitmap the same size as [src], with the original colours and a soft alpha
     * from the matte (transparent where background). Null if the model isn't available / fails.
     */
    fun cutout(ctx: Context, src: Bitmap): Bitmap? {
        val s = ensureSession(ctx) ?: run { Log.w("MattingModel", "no session (model not downloaded or failed to load) - falling back"); return null }
        val environment = env ?: return null
        return try {
            val inName = s.inputNames.iterator().next()
            val info = s.inputInfo[inName]!!.info as TensorInfo
            val shp = info.shape                             // [1,3,H,W]; dims may be -1 (dynamic)
            val fixedH = shp.getOrElse(2) { -1 }.toInt()
            val fixedW = shp.getOrElse(3) { -1 }.toInt()

            val (inW, inH) = if (fixedW > 0 && fixedH > 0) {
                fixedW to fixedH                              // fixed-size model: stretch to it
            } else {
                // dynamic: preserve aspect, long side ~1024 (sharper hair than 512), both multiples of 32
                val ref = 1024f
                val long = max(src.width, src.height).toFloat()
                val sc = (ref / long).coerceAtMost(1f)       // never upscale past the source
                fun m32(v: Int) = (max(1, (v / 32.0).roundToInt()) * 32)
                m32((src.width * sc).roundToInt()) to m32((src.height * sc).roundToInt())
            }

            val resized = Bitmap.createScaledBitmap(src, inW, inH, true)
            val px = IntArray(inW * inH); resized.getPixels(px, 0, inW, 0, 0, inW, inH)
            if (resized != src) resized.recycle()
            val plane = inW * inH
            val chw = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = px[i]
                chw[i] = (((p shr 16) and 0xFF) / 127.5f) - 1f               // R
                chw[plane + i] = (((p shr 8) and 0xFF) / 127.5f) - 1f        // G
                chw[2 * plane + i] = ((p and 0xFF) / 127.5f) - 1f            // B
            }
            val tensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(chw), longArrayOf(1, 3, inH.toLong(), inW.toLong())
            )
            val outName = s.outputNames.iterator().next()
            val result = s.run(mapOf(inName to tensor))
            val outTensor = result[0] as OnnxTensor
            val oShape = (outTensor.info as TensorInfo).shape
            val oH = oShape.getOrElse(oShape.size - 2) { inH.toLong() }.toInt()
            val oW = oShape.getOrElse(oShape.size - 1) { inW.toLong() }.toInt()
            val buf = outTensor.floatBuffer
            val matte = IntArray(oW * oH)
            for (i in 0 until oW * oH) {
                val a = (buf.get(i) * 255f).roundToInt().coerceIn(0, 255)
                matte[i] = (a shl 24) or 0x00FFFFFF          // store alpha, white rgb
            }
            tensor.close(); result.close()

            // scale the matte (with bilinear) up to the source size and apply it as alpha
            val matteBmp = Bitmap.createBitmap(oW, oH, Bitmap.Config.ARGB_8888)
            matteBmp.setPixels(matte, 0, oW, 0, 0, oW, oH)
            val scaled = Bitmap.createScaledBitmap(matteBmp, src.width, src.height, true)
            matteBmp.recycle()

            val w = src.width; val h = src.height
            val sPix = IntArray(w * h); src.getPixels(sPix, 0, w, 0, 0, w, h)
            val aPix = IntArray(w * h); scaled.getPixels(aPix, 0, w, 0, 0, w, h)
            if (scaled != matteBmp) scaled.recycle()
            val out = IntArray(w * h)
            for (i in sPix.indices) {
                val a = (aPix[i] ushr 24) and 0xFF
                out[i] = (a shl 24) or (sPix[i] and 0x00FFFFFF)
            }
            Log.i("MattingModel", "MODNet cutout ok (fed ${inW}x${inH})")
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
        } catch (e: Throwable) {
            Log.e("MattingModel", "MODNet cutout failed", e)
            null
        }
    }
}
