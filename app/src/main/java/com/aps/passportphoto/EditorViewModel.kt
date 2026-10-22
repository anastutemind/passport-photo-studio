package com.aps.passportphoto

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.print.PrintHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class SizePreset(val label: String, val w: Float, val h: Float)
data class PagePreset(val label: String, val w: Float, val h: Float)
class StripLine(on: Boolean, text: String) {
    var on by mutableStateOf(on)
    var text by mutableStateOf(text)
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        val SIZE_PRESETS = listOf(
            SizePreset("35 x 45 mm (ID / visa)", 35f, 45f),
            SizePreset("51 x 51 mm (2x2 in)", 51f, 51f),
            SizePreset("33 x 48 mm", 33f, 48f),
            SizePreset("25 x 35 mm (stamp)", 25f, 35f),
        )
        val PAGE_PRESETS = listOf(
            PagePreset("A4 (210 x 297 mm)", 210f, 297f),
            PagePreset("Letter (8.5 x 11 in)", 215.9f, 279.4f),
            PagePreset("A5 (148 x 210 mm)", 148f, 210f),
            PagePreset("4 x 6 in", 101.6f, 152.4f),
        )
    }

    // --- source / foreground ---
    private var src: Bitmap? = null
    private var fg: Bitmap? = null
    var bgRemoved by mutableStateOf(false); private set

    // --- transform ---
    var scale by mutableStateOf(1f)
    var offX by mutableStateOf(0f)
    var offY by mutableStateOf(0f)
    var rot by mutableStateOf(0f)

    // --- adjustments ---
    var brightness by mutableStateOf(1f)
    var contrast by mutableStateOf(1f)
    var saturation by mutableStateOf(1f)
    var skinBlur by mutableStateOf(0f)

    // --- background ---
    var bgColor by mutableStateOf(Color.WHITE)

    // --- size / page ---
    var preset by mutableStateOf(SIZE_PRESETS[0])
    var page by mutableStateOf(PAGE_PRESETS[0])
    var margin by mutableStateOf(5f)
    var gap by mutableStateOf(2f)

    // --- strip ---
    var stripOn by mutableStateOf(true)
    var stripH by mutableStateOf(8f)
    var stripBg by mutableStateOf(Color.WHITE)
    var stripFg by mutableStateOf(Color.BLACK)
    val stripLines = mutableStateListOf(
        StripLine(true, ""),
        StripLine(true, ""),
        StripLine(true, "Date: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())),
    )

    // --- ui state ---
    var preview by mutableStateOf<Bitmap?>(null); private set
    var busy by mutableStateOf(false); private set
    var busyMsg by mutableStateOf("")
    var message by mutableStateOf("Choose a photo to begin.")
    val hasImage: Boolean get() = src != null

    private val previewHeightPx = 680
    private var previewJob: Job? = null

    // ---------------------------------------------------------- load
    fun loadUri(uri: Uri) {
        viewModelScope.launch {
            busy = true; busyMsg = "Loading photo..."
            try {
                val bmp = withContext(Dispatchers.Default) { ImageUtils.decodeBitmap(getApplication(), uri) }
                src = bmp
                fg = null; bgRemoved = false
                scale = 1f; offX = 0f; offY = 0f; rot = 0f
                message = "Photo loaded. Try \"Auto align\"."
                refresh()
            } catch (e: Exception) {
                message = "Could not read image: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun reset() {
        scale = 1f; offX = 0f; offY = 0f; rot = 0f
        refresh()
    }

    fun clear() {
        src = null; fg = null; bgRemoved = false; preview = null
        message = "Choose a photo to begin."
    }

    // ---------------------------------------------------------- auto align
    fun autoAlign() {
        val image = src ?: return
        viewModelScope.launch {
            busy = true; busyMsg = "Detecting face..."
            try {
                val opts = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
                val detector = FaceDetection.getClient(opts)
                val faces = detector.process(InputImage.fromBitmap(image, 0)).await()
                detector.close()
                if (faces.isEmpty()) {
                    message = "No face found - position it manually with the sliders."
                    return@launch
                }
                val face = faces[0]
                val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                val box = face.boundingBox
                val imgW = image.width.toFloat()
                val imgH = image.height.toFloat()
                val W = ImageUtils.mmToPx(preset.w).toFloat()
                val H = ImageUtils.mmToPx(preset.h).toFloat()
                val baseScale = max(W / imgW, H / imgH)
                val faceBoxPx = box.height().toFloat()
                val s = 0.60f * H / faceBoxPx

                val fx: Float; val fy: Float; val angle: Float
                if (le != null && re != null) {
                    fx = (le.x + re.x) / 2f
                    fy = (le.y + re.y) / 2f
                    angle = Math.toDegrees(
                        Math.atan2((le.y - re.y).toDouble(), (le.x - re.x).toDouble())
                    ).toFloat()
                } else {
                    fx = box.exactCenterX(); fy = box.exactCenterY(); angle = 0f
                }
                rot = -angle
                scale = s / baseScale
                offX = -s * (fx - imgW / 2f) / W
                offY = -0.08f - s * (fy - imgH / 2f) / H
                message = "Face aligned. Fine-tune with sliders if needed."
                refresh()
            } catch (e: Exception) {
                message = "Auto align failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ---------------------------------------------------------- background removal
    fun removeBackground() {
        val image = src ?: return
        viewModelScope.launch {
            busy = true; busyMsg = "Removing background (first run downloads a model)..."
            try {
                val options = SubjectSegmenterOptions.Builder()
                    .enableForegroundBitmap()
                    .build()
                val segmenter = SubjectSegmentation.getClient(options)
                val result = segmenter.process(InputImage.fromBitmap(image, 0)).await()
                segmenter.close()
                val foreground = result.foregroundBitmap
                if (foreground == null) {
                    message = "Could not separate the subject. Try a clearer photo."
                    return@launch
                }
                fg = foreground.copy(Bitmap.Config.ARGB_8888, false)
                bgRemoved = true
                message = "Background removed. Pick a background color."
                refresh()
            } catch (e: Exception) {
                message = "Background removal failed (needs internet once to fetch model): ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ---------------------------------------------------------- rendering
    private fun activeBitmap(): Bitmap? = if (bgRemoved && fg != null) fg else src

    /** Build one finished photo (image area + optional strip) with the photo area [photoHpx] tall. */
    private fun buildFinal(photoHpx: Int): Bitmap? {
        val img = activeBitmap() ?: return null
        val aspect = preset.w / preset.h
        val ph = photoHpx
        val pw = max(1, Math.round(photoHpx * aspect))
        val sh = if (stripOn) Math.round(ph * (stripH / preset.h)) else 0

        val photo = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val pc = Canvas(photo)
        pc.drawColor(if (bgRemoved) bgColor else Color.WHITE)

        val baseScale = max(pw.toFloat() / img.width, ph.toFloat() / img.height)
        val s = baseScale * scale
        val m = Matrix().apply {
            postTranslate(-img.width / 2f, -img.height / 2f)
            postScale(s, s)
            postRotate(rot)
            postTranslate(pw / 2f + offX * pw, ph / 2f + offY * ph)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            colorFilter = android.graphics.ColorMatrixColorFilter(
                ImageUtils.colorMatrix(brightness, contrast, saturation)
            )
        }
        pc.drawBitmap(img, m, paint)
        ImageUtils.applySkinBlur(photo, skinBlur)

        if (sh == 0) return photo

        val out = Bitmap.createBitmap(pw, ph + sh, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(photo, 0f, 0f, null)
        photo.recycle()
        drawStrip(oc, pw, ph, sh)
        return out
    }

    private fun drawStrip(c: Canvas, pw: Int, ph: Int, sh: Int) {
        val bgPaint = Paint().apply { color = stripBg }
        c.drawRect(0f, ph.toFloat(), pw.toFloat(), (ph + sh).toFloat(), bgPaint)
        val lines = stripLines.filter { it.on && it.text.isNotBlank() }
        if (lines.isEmpty()) return
        val pad = sh * 0.14f
        val avail = sh - 2 * pad
        val lineH = avail / lines.size
        val fontSize = (lineH * 0.78f).coerceAtMost(sh * 0.5f).coerceAtLeast(8f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stripFg
            textSize = fontSize
            textAlign = Paint.Align.CENTER
        }
        lines.forEachIndexed { i, ln ->
            val cy = ph + pad + lineH * (i + 0.5f)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            c.drawText(ln.text, pw / 2f, baseline, textPaint)
        }
    }

    private fun refresh() {
        if (src == null) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(60)
            val bmp = withContext(Dispatchers.Default) { buildFinal(previewHeightPx) }
            if (bmp != null) preview = bmp
        }
    }

    /** generic setter used by sliders so each change re-renders the preview */
    fun onParamChanged() = refresh()

    // ---------------------------------------------------------- sheet
    fun fitCount(): Triple<Int, Int, Int> {
        val PW = ImageUtils.mmToPx(page.w)
        val PH = ImageUtils.mmToPx(page.h)
        val cw = ImageUtils.mmToPx(preset.w)
        val ch = ImageUtils.mmToPx(preset.h) + if (stripOn) ImageUtils.mmToPx(stripH) else 0
        val g = ImageUtils.mmToPx(gap)
        val mg = ImageUtils.mmToPx(margin)
        val cols = max(1, (PW - 2 * mg + g) / (cw + g))
        val rows = max(1, (PH - 2 * mg + g) / (ch + g))
        return Triple(cols * rows, cols, rows)
    }

    private fun buildSheet(): Bitmap {
        val PW = ImageUtils.mmToPx(page.w)
        val PH = ImageUtils.mmToPx(page.h)
        val one = buildFinal(ImageUtils.mmToPx(preset.h))!!
        val cw = one.width
        val ch = one.height
        val g = ImageUtils.mmToPx(gap)
        val mg = ImageUtils.mmToPx(margin)
        val cols = max(1, (PW - 2 * mg + g) / (cw + g))
        val rows = max(1, (PH - 2 * mg + g) / (ch + g))
        val sheet = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888)
        val sc = Canvas(sheet)
        sc.drawColor(Color.WHITE)
        val totalW = cols * cw + (cols - 1) * g
        val totalH = rows * ch + (rows - 1) * g
        val startX = (PW - totalW) / 2f
        val startY = max(mg.toFloat(), (PH - totalH) / 2f)
        for (r in 0 until rows) for (col in 0 until cols) {
            sc.drawBitmap(one, startX + col * (cw + g), startY + r * (ch + g), null)
        }
        one.recycle()
        return sheet
    }

    // ---------------------------------------------------------- export
    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()

    fun exportJpg() {
        if (src == null) return
        viewModelScope.launch {
            busy = true; busyMsg = "Building JPG sheet..."
            try {
                val uri = withContext(Dispatchers.Default) {
                    val sheet = buildSheet()
                    val name = "passport_${System.currentTimeMillis()}.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PassportPhoto")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = getApplication<Application>().contentResolver
                    val u = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    resolver.openOutputStream(u)!!.use { sheet.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(u, values, null, null)
                    sheet.recycle()
                    u
                }
                message = "Saved to Pictures/PassportPhoto"
                toast("JPG saved to Pictures/PassportPhoto")
            } catch (e: Exception) {
                message = "Save JPG failed: ${e.message}"
            } finally { busy = false }
        }
    }

    fun exportPdf() {
        if (src == null) return
        viewModelScope.launch {
            busy = true; busyMsg = "Building PDF..."
            try {
                withContext(Dispatchers.Default) {
                    val sheet = buildSheet()
                    val wPt = (page.w / 25.4f * 72f).toInt()
                    val hPt = (page.h / 25.4f * 72f).toInt()
                    val doc = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(wPt, hPt, 1).create()
                    val pdfPage = doc.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(
                        sheet, Rect(0, 0, sheet.width, sheet.height), Rect(0, 0, wPt, hPt),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    doc.finishPage(pdfPage)
                    val name = "passport_${System.currentTimeMillis()}.pdf"
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PassportPhoto")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = getApplication<Application>().contentResolver
                    val u = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    resolver.openOutputStream(u)!!.use { doc.writeTo(it) }
                    doc.close()
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(u, values, null, null)
                    sheet.recycle()
                }
                message = "Saved to Download/PassportPhoto"
                toast("PDF saved to Download/PassportPhoto")
            } catch (e: Exception) {
                message = "Save PDF failed: ${e.message}"
            } finally { busy = false }
        }
    }

    fun print() {
        if (src == null) return
        viewModelScope.launch {
            busy = true; busyMsg = "Preparing print..."
            try {
                val sheet = withContext(Dispatchers.Default) { buildSheet() }
                val helper = PrintHelper(getApplication()).apply {
                    scaleMode = PrintHelper.SCALE_MODE_FIT
                    colorMode = PrintHelper.COLOR_MODE_COLOR
                }
                helper.printBitmap("Passport photo sheet", sheet)
            } catch (e: Exception) {
                message = "Print failed: ${e.message}"
            } finally { busy = false }
        }
    }
}
