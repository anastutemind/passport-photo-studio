package com.aps.passportphoto

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.print.PrintHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** A document photo spec. headTop/chin are the ideal top-of-head and chin positions as a
 *  fraction of the photo height (from the top), used to draw the compliance guides. */
data class SizeSpec(
    val country: String, val doc: String, val w: Float, val h: Float,
    val headTop: Float, val chin: Float
) {
    val label get() = "$country"
    val sub get() = "$doc  -  ${w.toInt()} x ${h.toInt()} mm"
}
data class PagePreset(val label: String, val w: Float, val h: Float)
class StripLine(on: Boolean, text: String) {
    var on by mutableStateOf(on)
    var text by mutableStateOf(text)
}

enum class StripPlacement { BELOW, OVERLAY }
enum class StripBgMode { FULL, TEXT }
enum class BrushTool { ERASE, RESTORE, BURN, DODGE }

/** Facial feature outlines (source-image coordinates, interleaved x,y) for makeup rendering. */
class FaceMakeupData(
    val faceOval: FloatArray,
    val upperLipTop: FloatArray, val upperLipBottom: FloatArray,
    val lowerLipTop: FloatArray, val lowerLipBottom: FloatArray,
    val leftEye: FloatArray, val rightEye: FloatArray,
    val leftBrowTop: FloatArray, val leftBrowBottom: FloatArray,
    val rightBrowTop: FloatArray, val rightBrowBottom: FloatArray,
    val leftCheek: FloatArray?, val rightCheek: FloatArray?
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        val SIZE_CATALOG = listOf(
            SizeSpec("India", "Passport / Visa / PAN", 35f, 45f, 0.11f, 0.82f),
            SizeSpec("USA", "Passport / Visa / Green Card", 51f, 51f, 0.14f, 0.74f),
            SizeSpec("UK", "Passport / Visa", 35f, 45f, 0.12f, 0.82f),
            SizeSpec("Schengen / EU", "Passport / Visa", 35f, 45f, 0.11f, 0.83f),
            SizeSpec("Germany", "Passport / ID", 35f, 45f, 0.11f, 0.83f),
            SizeSpec("France", "Passport / ID", 35f, 45f, 0.11f, 0.83f),
            SizeSpec("China", "Passport / Visa", 33f, 48f, 0.13f, 0.80f),
            SizeSpec("Canada", "Passport", 50f, 70f, 0.19f, 0.69f),
            SizeSpec("Japan", "Passport / Visa", 35f, 45f, 0.12f, 0.82f),
            SizeSpec("Australia", "Passport / Visa", 35f, 45f, 0.12f, 0.82f),
            SizeSpec("UAE", "Passport / Visa", 43f, 55f, 0.13f, 0.80f),
            SizeSpec("Pakistan", "Passport / NICOP", 35f, 45f, 0.11f, 0.82f),
            SizeSpec("Generic", "35 x 45 mm", 35f, 45f, 0.11f, 0.82f),
            SizeSpec("Generic", "2 x 2 in (51 mm)", 51f, 51f, 0.14f, 0.74f),
            SizeSpec("Stamp", "25 x 35 mm", 25f, 35f, 0.11f, 0.82f),
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
    private var fgRaw: Bitmap? = null
    var bgRemoved by mutableStateOf(false); private set
    var defringeStrength by mutableStateOf(0.9f)   // 0..1 hair edge foreground estimation

    // export preview (what will actually be exported)
    var exportPreview by mutableStateOf<Bitmap?>(null); private set

    // --- transform ---
    var scale by mutableStateOf(1f)
    var offX by mutableStateOf(0f)
    var offY by mutableStateOf(0f)
    var rot by mutableStateOf(0f)

    // --- adjustments ---
    var brightness by mutableStateOf(1f)
    var contrast by mutableStateOf(1f)
    var saturation by mutableStateOf(1f)
    var whiteBalance by mutableStateOf(0f)
    var skinSmooth by mutableStateOf(0f)
    var blemish by mutableStateOf(0f)
    var skinLighten by mutableStateOf(0f)
    var sharpen by mutableStateOf(0f)

    // --- background ---
    var bgColor by mutableStateOf(Color.WHITE)

    // --- size / page ---
    var preset by mutableStateOf(SIZE_CATALOG[0])
    var page by mutableStateOf(PAGE_PRESETS[0])
    var showGuides by mutableStateOf(true)
    var margin by mutableStateOf(1f)
    var gap by mutableStateOf(1f)

    // --- strip --- (off by default; user turns it on, and defaults to hugging the text)
    var stripOn by mutableStateOf(false)
    var stripPlacement by mutableStateOf(StripPlacement.OVERLAY)
    var stripH by mutableStateOf(8f)
    var stripWidthFrac by mutableStateOf(1f)
    var stripBgMode by mutableStateOf(StripBgMode.TEXT)
    var stripBg by mutableStateOf(Color.WHITE)
    var stripFg by mutableStateOf(Color.BLACK)
    var stripBorderOn by mutableStateOf(false)
    var stripBorderColor by mutableStateOf(Color.BLACK)
    var stripBorderMm by mutableStateOf(0.3f)
    var stripFontIndex by mutableStateOf(0)
    var stripBold by mutableStateOf(false)
    var stripFontScale by mutableStateOf(1f)
    val stripFonts = listOf("Arial", "Impact", "Times", "PT Serif", "Oswald")
    private val stripFontAssets = listOf(
        "fonts/Arimo-Regular.ttf", "fonts/Anton-Regular.ttf", "fonts/Tinos-Regular.ttf",
        "fonts/PTSerif-Regular.ttf", "fonts/Oswald-Regular.ttf"
    )
    private val tfCache = HashMap<String, Typeface>()
    private fun stripTypeface(): Typeface {
        val path = stripFontAssets.getOrElse(stripFontIndex) { stripFontAssets[0] }
        val base = tfCache.getOrPut(path) {
            try { Typeface.createFromAsset(getApplication<Application>().assets, path) } catch (e: Exception) { Typeface.DEFAULT }
        }
        return if (stripBold) Typeface.create(base, Typeface.BOLD) else base
    }

    // --- cut border ---
    var borderOn by mutableStateOf(true)
    var borderColor by mutableStateOf(Color.rgb(140, 140, 140))
    var borderMm by mutableStateOf(0.3f)

    // --- export target ---
    var exportSingle by mutableStateOf(false)

    // --- attire overlay (photoreal, bundled transparent PNGs) ---
    var attireIndex by mutableStateOf(0)
    var clothingX by mutableStateOf(0f)
    var clothingPos by mutableStateOf(0.55f)
    var clothingSize by mutableStateOf(1.05f)
    val attirePresets = listOf("None", "Shirt + tie", "Shirt + navy tie", "White shirt", "Charcoal suit", "Navy suit")
    private val attireAssets = listOf(null, "attire/stock_tie1.png", "attire/stock_tie2.png", "attire/white.png", "attire/charcoal.png", "attire/navy.png")
    private val attireCache = HashMap<String, Bitmap>()
    private fun attireBitmap(path: String): Bitmap? {
        attireCache[path]?.let { return it }
        return try {
            getApplication<Application>().assets.open(path).use {
                BitmapFactory.decodeStream(it)?.also { bmp -> attireCache[path] = bmp }
            }
        } catch (e: Exception) { null }
    }
    fun setAttire(i: Int) { attireIndex = i; onParamChanged() }
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
                fc = null; makeupReady = false
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

    /** Start over: drop the photo and reset all per-photo edits (keeps the chosen document & strip template). */
    fun newSession() {
        src = null; fg = null; fgRaw = null; bgRemoved = false; preview = null; exportPreview = null
        scale = 1f; offX = 0f; offY = 0f; rot = 0f
        brightness = 1f; contrast = 1f; saturation = 1f; whiteBalance = 0f
        skinSmooth = 0f; blemish = 0f; skinLighten = 0f; sharpen = 0f; defringeStrength = 0.9f
        bgColor = Color.WHITE
        attireIndex = 0; clothingX = 0f; clothingPos = 0.55f; clothingSize = 1.05f
        stripOn = false
        touchUpOpen = false; editDraw = false; touchUpBmp = null; editSrc = null; editMask = null; checker = null
        cutBuf = null; outBuf0 = null; outBuf1 = null; baseMaskAlpha = null; baseColor = null; burnBuf = null; undoStack.clear()
        editW = 0; editH = 0; editBg = 0
        brushTool = BrushTool.ERASE; brushSize = 0.07f; brushSoft = 0.5f; brushEdge = false; brushStrength = 0.5f
        fc = null; makeupReady = false
        lipOn = false; eyelinerOn = false; lashesOn = false; browOn = false; blushOn = false; foundationOn = false; powderOn = false
        lipsX = 0f; lipsY = 0f; lipsW = 1f; lipsH = 1f; eyesX = 0f; eyesY = 0f; eyesW = 1f; eyesH = 1f
        browsX = 0f; browsY = 0f; browsW = 1f; browsH = 1f; blushX = 0f; blushY = 0f; blushW = 1f; blushH = 1f
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

                val fx: Float; val fy: Float
                if (le != null && re != null) {
                    fx = (le.x + re.x) / 2f
                    fy = (le.y + re.y) / 2f
                } else {
                    fx = box.exactCenterX(); fy = box.exactCenterY()
                }
                // Use the detector's roll angle, clamped, so a mis-detection can never flip the photo.
                rot = (-face.headEulerAngleZ).coerceIn(-30f, 30f)
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

    /** Snap the head so the crown sits on the HEAD guide and chin on the CHIN guide of the current spec. */
    fun autoFitToGuide() {
        val image = src ?: return
        viewModelScope.launch {
            busy = true; busyMsg = "Fitting to guide..."
            try {
                val opts = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
                val detector = FaceDetection.getClient(opts)
                val faces = detector.process(InputImage.fromBitmap(image, 0)).await()
                detector.close()
                if (faces.isEmpty()) { message = "No face found - adjust manually."; return@launch }
                val face = faces[0]
                val box = face.boundingBox
                val imgW = image.width.toFloat(); val imgH = image.height.toFloat()
                val W = ImageUtils.mmToPx(preset.w).toFloat(); val H = ImageUtils.mmToPx(preset.h).toFloat()
                val baseScale = max(W / imgW, H / imgH)
                // estimate crown (above box top) and chin (near box bottom) of the full head
                val chinSrc = box.bottom + 0.06f * box.height()
                val crownSrc = box.top - 0.45f * box.height()
                val headSrc = (chinSrc - crownSrc).coerceAtLeast(1f)
                val headCenterSrc = (chinSrc + crownSrc) / 2f
                // when a strip covers the bottom, fit the head into the photo area above it
                val usable = if (stripOn) (1f - stripH / preset.h).coerceIn(0.4f, 1f) else 1f
                val headTopF = preset.headTop * usable; val chinF = preset.chin * usable
                val targetHeadPx = (chinF - headTopF) * H
                val sAbs = targetHeadPx / headSrc
                rot = (-face.headEulerAngleZ).coerceIn(-30f, 30f)
                scale = sAbs / baseScale
                offX = -sAbs * (box.exactCenterX() - imgW / 2f) / W
                offY = (headTopF + chinF) / 2f - 0.5f - sAbs * (headCenterSrc - imgH / 2f) / H
                message = "Fitted to the ${preset.label} guide."
                refresh()
            } catch (e: Exception) {
                message = "Auto-fit failed: ${e.message}"
            } finally { busy = false }
        }
    }

    // ---------------------------------------------------------- background removal
    fun removeBackground() {
        val image = src ?: return
        viewModelScope.launch {
            busy = true
            val ctx = getApplication<Application>()
            busyMsg = if (MattingModel.isModelCached(ctx)) "Removing background..."
                      else "Preparing the matting model (one-time ~26 MB download)..."
            try {
                // 1) MODNet soft alpha matte on-device - keeps hair detail.
                //    Do NOT harden this matte (edgeRefine=0): the soft hair wisps are the whole point.
                val cut = withContext(Dispatchers.Default) { MattingModel.cutout(ctx, image) }
                if (cut != null) {
                    fgRaw = cut
                    bgRemoved = true
                    defringeStrength = 0.9f
                    applyRefine()
                    message = "Background removed (AI matte). Use Brush touch-up to fix edges, or \"Restore original\" to undo."
                    return@launch
                }
                // 2) fallback: ML Kit subject segmentation (if the model couldn't download/run)
                busyMsg = "Removing background (basic mode)..."
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
                fgRaw = ImageUtils.alphaFrom(image, foreground)   // keep real photo colours, not black
                bgRemoved = true
                defringeStrength = 0.9f
                applyRefine()
                message = "Background removed (basic mode). Connect to the internet once so the matting model can download for sharper hair edges."
            } catch (e: Exception) {
                message = "Background removal failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    /** Revert to the original photo background (keeps the computed cut-out so it can be re-applied instantly). */
    fun restoreBackground() {
        if (!bgRemoved) return
        bgRemoved = false
        message = "Original background restored."
        refresh()
    }

    /** Re-apply the already-computed cut-out without re-running segmentation. */
    fun reapplyBackground() {
        if (bgRemoved) return
        if (fgRaw == null) { removeBackground(); return }
        bgRemoved = true
        message = "Background removed again."
        refresh()
    }

    // ---------------------------------------------------------- brush touch-up (mask editor, v2)
    var touchUpOpen by mutableStateOf(false)
    var editDraw by mutableStateOf(false)         // false = move/zoom the image; true = paint
    var brushTool by mutableStateOf(BrushTool.ERASE)
    var brushSize by mutableStateOf(0.07f)        // radius as a fraction of the edit image's short side
    var brushSoft by mutableStateOf(0.5f)         // 0 = hard edge, 1 = very soft
    var brushEdge by mutableStateOf(false)        // smart edge mode: preserve detail, remove only unwanted
    var brushStrength by mutableStateOf(0.5f)     // burn/dodge strength
    var brushOffset by mutableStateOf(90f)        // dp the ring sits above the fingertip
    var editBg by mutableStateOf(0)               // editor backdrop: 0 checker, 1 white, 2 black, 3 grey
    var touchUpBmp by mutableStateOf<Bitmap?>(null); private set

    private var editW = 0; private var editH = 0
    private var burnBuf: FloatArray? = null        // per-pixel burn(-)/dodge(+) amount, so it never compounds to black
    private var editSrc: Bitmap? = null           // photo RGB at editing resolution (opaque)
    private var editMask: Bitmap? = null          // ARGB; ALPHA = subject coverage (255 keep, 0 removed)
    private var checker: Bitmap? = null           // cached transparency checkerboard
    private var cutBuf: Bitmap? = null            // reused scratch buffers (no per-frame allocation)
    private var outBuf0: Bitmap? = null
    private var outBuf1: Bitmap? = null
    private var outToggle = false
    private var baseMaskAlpha: ByteArray? = null  // original matte alpha for Reset
    private var baseColor: IntArray? = null       // original edit-res colours for Reset
    private class UndoEntry(val alpha: ByteArray?, val color: IntArray?, val burn: FloatArray?)
    private val undoStack = ArrayDeque<UndoEntry>()
    private fun pixelsOf(b: Bitmap): IntArray { val a = IntArray(b.width * b.height); b.getPixels(a, 0, b.width, 0, 0, b.width, b.height); return a }
    private val EDIT_MAX = 1200
    private val clearPaint = Paint()
    private val dstInPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private fun alphaBytes(b: Bitmap): ByteArray {
        val n = b.width * b.height; val px = IntArray(n); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val a = ByteArray(n); for (i in 0 until n) a[i] = ((px[i] ushr 24) and 0xFF).toByte(); return a
    }
    private fun setAlphaBytes(b: Bitmap, a: ByteArray) {
        val n = b.width * b.height; val px = IntArray(n)
        for (i in 0 until n) px[i] = ((a[i].toInt() and 0xFF) shl 24) or 0x00FFFFFF
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    }
    private fun buildChecker(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.rgb(214, 218, 224))
        val cell = (minOf(w, h) / 20).coerceAtLeast(12)
        val dark = Paint().apply { color = Color.rgb(176, 182, 190) }
        var y = 0; var row = 0
        while (y < h) { var x = 0; var p = row; while (x < w) { if ((p and 1) == 1) c.drawRect(x.toFloat(), y.toFloat(), (x + cell).toFloat(), (y + cell).toFloat(), dark); x += cell; p++ }; y += cell; row++ }
        return bmp
    }

    fun openTouchUp() {
        val raw = fgRaw ?: return; val s = src ?: return
        editDraw = false
        viewModelScope.launch {
            busy = true; busyMsg = "Preparing editor..."
            withContext(Dispatchers.Default) {
                val longSide = max(s.width, s.height)
                val sc = if (longSide > EDIT_MAX) EDIT_MAX.toFloat() / longSide else 1f
                val ew = Math.round(s.width * sc).coerceAtLeast(1); val eh = Math.round(s.height * sc).coerceAtLeast(1)
                val es = Bitmap.createScaledBitmap(s, ew, eh, true).copy(Bitmap.Config.ARGB_8888, true)
                run { val p = IntArray(ew * eh); es.getPixels(p, 0, ew, 0, 0, ew, eh); for (i in p.indices) p[i] = p[i] or 0xFF000000.toInt(); es.setPixels(p, 0, ew, 0, 0, ew, eh) }
                val rawScaled = Bitmap.createScaledBitmap(raw, ew, eh, true)
                val mk = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
                run { val p = IntArray(ew * eh); rawScaled.getPixels(p, 0, ew, 0, 0, ew, eh); for (i in p.indices) p[i] = (p[i] and 0xFF000000.toInt()) or 0x00FFFFFF; mk.setPixels(p, 0, ew, 0, 0, ew, eh) }
                if (rawScaled != raw) rawScaled.recycle()
                editSrc = es; editMask = mk; checker = buildChecker(ew, eh)
                cutBuf = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
                outBuf0 = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
                outBuf1 = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
                editW = ew; editH = eh; burnBuf = FloatArray(ew * eh)
                baseMaskAlpha = alphaBytes(mk); baseColor = pixelsOf(es); undoStack.clear()
            }
            recomposeEditor(); touchUpOpen = true; busy = false
        }
    }

    fun recomposeEditor() {
        val es = editSrc ?: return; val m = editMask ?: return
        val cut = cutBuf ?: return
        val cc = Canvas(cut)
        cc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        cc.drawBitmap(es, 0f, 0f, null)
        cc.drawBitmap(m, 0f, 0f, dstInPaint)              // keep photo only where mask alpha
        val out = (if (outToggle) outBuf0 else outBuf1) ?: return; outToggle = !outToggle
        val oc = Canvas(out)
        when (editBg) {                                   // backdrop so edges are visible on any colour
            1 -> oc.drawColor(Color.WHITE)
            2 -> oc.drawColor(Color.rgb(12, 12, 14))
            3 -> oc.drawColor(Color.rgb(128, 130, 134))
            else -> checker?.let { oc.drawBitmap(it, 0f, 0f, null) } ?: oc.drawColor(Color.rgb(214, 218, 224))
        }
        oc.drawBitmap(cut, 0f, 0f, null)
        touchUpBmp = out
    }

    /** Stamp the brush at edit-image coordinates (called live as the finger moves). */
    fun paintAt(x: Float, y: Float) {
        when (brushTool) {
            BrushTool.ERASE -> if (brushEdge) paintEdgeErase(x, y) else paintAlpha(x, y)
            BrushTool.RESTORE -> paintRestore(x, y)
            BrushTool.BURN -> paintBurnDodge(x, y, true)
            BrushTool.DODGE -> paintBurnDodge(x, y, false)
        }
    }

    /** Plain soft erase: lower alpha exactly under the circle (GPU-fast). */
    private fun paintAlpha(x: Float, y: Float) {
        val m = editMask ?: return
        val r = (brushSize * minOf(m.width, m.height)).coerceAtLeast(2f)
        val hard = (1f - brushSoft).coerceIn(0f, 0.92f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT), floatArrayOf(0f, hard, 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        Canvas(m).drawCircle(x, y, r, paint)
        recomposeEditor()
    }

    private fun brushBox(x: Float, y: Float, w: Int, h: Int, r: Float): IntArray =
        intArrayOf((x - r).toInt().coerceIn(0, w - 1), (y - r).toInt().coerceIn(0, h - 1), (x + r).toInt().coerceIn(0, w - 1), (y + r).toInt().coerceIn(0, h - 1))

    private fun isSkinC(r: Int, g: Int, b: Int): Boolean {
        if (maxOf(r, g, b) < 60) return false                 // dark (hair/shadow) - not skin
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return r > g && cb in 77f..135f && cr in 133f..180f   // greys/whites fail cr -> burnable
    }

    private fun applyBurn(baseC: Int, amt: Float): Int {
        val r0 = (baseC shr 16) and 0xFF; val g0 = (baseC shr 8) and 0xFF; val b0 = baseC and 0xFF
        val r: Int; val g: Int; val bl: Int
        if (amt < 0f) { val f = 1f + amt * 0.85f; r = (r0 * f).toInt(); g = (g0 * f).toInt(); bl = (b0 * f).toInt() }
        else { r = (r0 + (255 - r0) * amt * 0.85f).toInt(); g = (g0 + (255 - g0) * amt * 0.85f).toInt(); bl = (b0 + (255 - b0) * amt * 0.85f).toInt() }
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
    }

    /** Edge-aware erase: within the circle, remove only the lighter (background) pixels and keep the
     *  darker hair - threshold adapts to the subject darkness under the brush. */
    private fun paintEdgeErase(x: Float, y: Float) {
        val m = editMask ?: return; val es = editSrc ?: return
        val w = m.width; val h = m.height
        val r = (brushSize * minOf(w, h)).coerceAtLeast(2f)
        val bx = brushBox(x, y, w, h, r); val x0 = bx[0]; val y0 = bx[1]; val bw = bx[2] - x0 + 1; val bh = bx[3] - y0 + 1
        if (bw <= 0 || bh <= 0) return
        val mp = IntArray(bw * bh); m.getPixels(mp, 0, bw, x0, y0, bw, bh)
        val sp = IntArray(bw * bh); es.getPixels(sp, 0, bw, x0, y0, bw, bh)
        val hard = (1f - brushSoft).coerceIn(0f, 0.95f)
        // adaptive subject luma = mean luma of the OPAQUE pixels under the brush
        var sum = 0f; var cnt = 0
        for (i in mp.indices) { if (((mp[i] ushr 24) and 0xFF) > 180) { val c = sp[i]; sum += ((c shr 16) and 0xFF) * 0.3f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f; cnt++ } }
        val subj = if (cnt > 10) sum / cnt else 60f
        val thr = subj + 35f
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val idx = yy * bw + xx
            val dx = (x0 + xx) - x; val dy = (y0 + yy) - y
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / r; if (d > 1f) continue
            val fall = if (d <= hard) 1f else (1f - (d - hard) / (1f - hard)).coerceIn(0f, 1f)
            val a = (mp[idx] ushr 24) and 0xFF
            val c = sp[idx]; val luma = ((c shr 16) and 0xFF) * 0.30f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f
            val bgness = ((luma - thr) / 80f).coerceIn(0f, 1f)          // brighter than subject -> background
            val na = (a * (1f - fall * bgness)).toInt().coerceIn(0, 255)
            mp[idx] = (na shl 24) or 0x00FFFFFF
        }
        m.setPixels(mp, 0, bw, x0, y0, bw, bh)
        recomposeEditor()
    }

    /** Restore: revert the painted area toward the ORIGINAL cut-out - raises alpha (brings back cut
     *  parts) AND clears any burn/dodge colour change. */
    private fun paintRestore(x: Float, y: Float) {
        val m = editMask ?: return; val es = editSrc ?: return
        val base = baseColor; val burn = burnBuf; val ew = editW
        val w = m.width; val h = m.height
        val r = (brushSize * minOf(w, h)).coerceAtLeast(2f)
        val bx = brushBox(x, y, w, h, r); val x0 = bx[0]; val y0 = bx[1]; val bw = bx[2] - x0 + 1; val bh = bx[3] - y0 + 1
        if (bw <= 0 || bh <= 0) return
        val mp = IntArray(bw * bh); m.getPixels(mp, 0, bw, x0, y0, bw, bh)
        val sp = IntArray(bw * bh); es.getPixels(sp, 0, bw, x0, y0, bw, bh)
        val hard = (1f - brushSoft).coerceIn(0f, 0.95f)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val idx = yy * bw + xx
            val dx = (x0 + xx) - x; val dy = (y0 + yy) - y
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / r; if (d > 1f) continue
            val fall = if (d <= hard) 1f else (1f - (d - hard) / (1f - hard)).coerceIn(0f, 1f)
            val a = (mp[idx] ushr 24) and 0xFF
            val na = (a + (255 - a) * fall).toInt().coerceIn(0, 255)      // bring back toward opaque
            mp[idx] = (na shl 24) or 0x00FFFFFF
            val gi = (y0 + yy) * ew + (x0 + xx)
            if (base != null && burn != null && gi in base.indices) {    // clear burn/dodge, revert colour
                burn[gi] *= (1f - fall)
                sp[idx] = applyBurn(base[gi], burn[gi])
            }
        }
        m.setPixels(mp, 0, bw, x0, y0, bw, bh)
        es.setPixels(sp, 0, bw, x0, y0, bw, bh)
        recomposeEditor()
    }

    /** Dodge/Burn: accumulate a bounded burn amount (never compounds to solid black), apply from the
     *  ORIGINAL colour, only on visible pixels. */
    private fun paintBurnDodge(x: Float, y: Float, darken: Boolean) {
        val es = editSrc ?: return; val m = editMask ?: return
        val base = baseColor ?: return; val burn = burnBuf ?: return; val ew = editW
        val w = es.width; val h = es.height
        val r = (brushSize * minOf(w, h)).coerceAtLeast(2f)
        val bx = brushBox(x, y, w, h, r); val x0 = bx[0]; val y0 = bx[1]; val bw = bx[2] - x0 + 1; val bh = bx[3] - y0 + 1
        if (bw <= 0 || bh <= 0) return
        val sp = IntArray(bw * bh); es.getPixels(sp, 0, bw, x0, y0, bw, bh)
        val mp = IntArray(bw * bh); m.getPixels(mp, 0, bw, x0, y0, bw, bh)
        val hard = (1f - brushSoft).coerceIn(0f, 0.95f)
        val step = brushStrength.coerceIn(0f, 1f) * 0.18f * (if (darken) -1f else 1f)
        for (yy in 0 until bh) for (xx in 0 until bw) {
            val idx = yy * bw + xx
            val a = (mp[idx] ushr 24) and 0xFF; if (a == 0) continue
            val gi = (y0 + yy) * ew + (x0 + xx); if (gi !in base.indices) continue
            // Protect SKIN by colour (so burn never blackens the face), but DO affect the bright hair
            // halo whether it's opaque or soft - that's the whole point of burn on hair.
            val bc = base[gi]; if (isSkinC((bc shr 16) and 0xFF, (bc shr 8) and 0xFF, bc and 0xFF)) continue
            val dx = (x0 + xx) - x; val dy = (y0 + yy) - y
            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / r; if (d > 1f) continue
            val fall = if (d <= hard) 1f else (1f - (d - hard) / (1f - hard)).coerceIn(0f, 1f)
            burn[gi] = (burn[gi] + step * fall).coerceIn(-1f, 1f)        // bounded accumulation
            sp[idx] = applyBurn(base[gi], burn[gi])
        }
        es.setPixels(sp, 0, bw, x0, y0, bw, bh)
        recomposeEditor()
    }

    fun strokeBegin() {
        // snapshot all three layers so erase/restore/burn/dodge all undo correctly
        val a = editMask?.let { alphaBytes(it) }
        val c = editSrc?.let { pixelsOf(it) }
        val b = burnBuf?.copyOf()
        undoStack.addLast(UndoEntry(a, c, b))
        while (undoStack.size > 8) undoStack.removeFirst()
    }
    fun undoTouchUp() {
        val e = undoStack.removeLastOrNull() ?: return
        e.alpha?.let { a -> editMask?.let { setAlphaBytes(it, a) } }
        e.color?.let { c -> editSrc?.let { it.setPixels(c, 0, it.width, 0, 0, it.width, it.height) } }
        e.burn?.let { bb -> burnBuf?.let { System.arraycopy(bb, 0, it, 0, minOf(bb.size, it.size)) } }
        recomposeEditor()
    }
    fun resetTouchUp() {
        editMask?.let { m -> baseMaskAlpha?.let { setAlphaBytes(m, it) } }
        editSrc?.let { s -> baseColor?.let { s.setPixels(it, 0, s.width, 0, 0, s.width, s.height) } }
        burnBuf?.let { java.util.Arrays.fill(it, 0f) }
        undoStack.clear(); recomposeEditor()
    }

    /** Commit the edited cut-out (colour + mask, at editing resolution) and close the editor. */
    fun closeTouchUp() {
        touchUpOpen = false
        val es = editSrc ?: return; val m = editMask ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                val w = es.width; val h = es.height; val n = w * h
                val sp = IntArray(n); es.getPixels(sp, 0, w, 0, 0, w, h)
                val mp = IntArray(n); m.getPixels(mp, 0, w, 0, 0, w, h)
                val out = IntArray(n); for (i in 0 until n) out[i] = (mp[i] and 0xFF000000.toInt()) or (sp[i] and 0x00FFFFFF)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.setPixels(out, 0, w, 0, 0, w, h)
                fgRaw = bmp; fg = bmp.copy(Bitmap.Config.ARGB_8888, true)
            }
            refresh()
        }
    }

    // ---------------------------------------------------------- makeup
    var makeupReady by mutableStateOf(false); private set
    private var fc: FaceMakeupData? = null
    var lipOn by mutableStateOf(false);        var lipIntensity by mutableStateOf(55f);        var lipColor by mutableStateOf(Color.rgb(176, 42, 66))
    var eyelinerOn by mutableStateOf(false);   var eyelinerIntensity by mutableStateOf(60f)
    var eyelinerTop by mutableStateOf(true);    var eyelinerBottom by mutableStateOf(false)
    var lashesOn by mutableStateOf(false);      var lashesIntensity by mutableStateOf(55f)
    var browOn by mutableStateOf(false);       var browIntensity by mutableStateOf(45f)
    var blushOn by mutableStateOf(false);      var blushIntensity by mutableStateOf(40f);       var blushColor by mutableStateOf(Color.rgb(226, 122, 122))
    var foundationOn by mutableStateOf(false); var foundationIntensity by mutableStateOf(40f)
    var powderOn by mutableStateOf(false);     var powderIntensity by mutableStateOf(40f)
    // per-item placement nudge (offset in face-width units + width/height scale about the item centre)
    var lipsX by mutableStateOf(0f); var lipsY by mutableStateOf(0f); var lipsW by mutableStateOf(1f); var lipsH by mutableStateOf(1f)
    var eyesX by mutableStateOf(0f); var eyesY by mutableStateOf(0f); var eyesW by mutableStateOf(1f); var eyesH by mutableStateOf(1f)
    var browsX by mutableStateOf(0f); var browsY by mutableStateOf(0f); var browsW by mutableStateOf(1f); var browsH by mutableStateOf(1f)
    var blushX by mutableStateOf(0f); var blushY by mutableStateOf(0f); var blushW by mutableStateOf(1f); var blushH by mutableStateOf(1f)

    val anyMakeup: Boolean get() = lipOn || eyelinerOn || lashesOn || browOn || blushOn || foundationOn || powderOn

    /** Detect facial feature contours for makeup (once per photo). */
    fun ensureFaceContours(onDone: (() -> Unit)? = null) {
        if (fc != null) { makeupReady = true; onDone?.invoke(); return }
        val image = src ?: return
        viewModelScope.launch {
            busy = true; busyMsg = "Detecting facial features..."
            try {
                val opts = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .build()
                val det = FaceDetection.getClient(opts)
                val faces = det.process(InputImage.fromBitmap(image, 0)).await()
                det.close()
                val f = faces.firstOrNull()
                if (f == null) { message = "No face found - makeup needs a visible face."; return@launch }
                fun pts(type: Int): FloatArray {
                    val c = f.getContour(type)?.points ?: return FloatArray(0)
                    val a = FloatArray(c.size * 2); c.forEachIndexed { i, p -> a[i * 2] = p.x; a[i * 2 + 1] = p.y }; return a
                }
                fun lm(type: Int): FloatArray? = f.getLandmark(type)?.position?.let { floatArrayOf(it.x, it.y) }
                fc = FaceMakeupData(
                    faceOval = pts(FaceContour.FACE),
                    upperLipTop = pts(FaceContour.UPPER_LIP_TOP), upperLipBottom = pts(FaceContour.UPPER_LIP_BOTTOM),
                    lowerLipTop = pts(FaceContour.LOWER_LIP_TOP), lowerLipBottom = pts(FaceContour.LOWER_LIP_BOTTOM),
                    leftEye = pts(FaceContour.LEFT_EYE), rightEye = pts(FaceContour.RIGHT_EYE),
                    leftBrowTop = pts(FaceContour.LEFT_EYEBROW_TOP), leftBrowBottom = pts(FaceContour.LEFT_EYEBROW_BOTTOM),
                    rightBrowTop = pts(FaceContour.RIGHT_EYEBROW_TOP), rightBrowBottom = pts(FaceContour.RIGHT_EYEBROW_BOTTOM),
                    leftCheek = lm(FaceLandmark.LEFT_CHEEK), rightCheek = lm(FaceLandmark.RIGHT_CHEEK)
                )
                makeupReady = true
                message = "Facial features detected. Toggle makeup items on."
                refresh()
                onDone?.invoke()
            } catch (e: Exception) {
                message = "Feature detection failed: ${e.message}"
            } finally { busy = false }
        }
    }

    private fun bandPath(top: FloatArray, bottom: FloatArray): Path {
        val p = Path()
        if (top.size < 2) return p
        p.moveTo(top[0], top[1]); var i = 2; while (i + 1 < top.size) { p.lineTo(top[i], top[i + 1]); i += 2 }
        var j = bottom.size - 2; while (j >= 0) { p.lineTo(bottom[j], bottom[j + 1]); j -= 2 }  // back along bottom
        p.close(); return p
    }
    private fun bounds(a: FloatArray): RectF {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var i = 0; while (i + 1 < a.size) { val x = a[i]; val y = a[i + 1]; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; i += 2 }
        return RectF(minX, minY, maxX, maxY)
    }

    /** Foundation + powder as genuine skin pixel ops, over an expanded face mask (covers forehead). */
    private fun applyFaceSkinMakeup(photo: Bitmap, m: Matrix) {
        if (!foundationOn && !powderOn) return
        val d = fc ?: return
        val face = d.faceOval.copyOf(); if (face.isEmpty()) return
        m.mapPoints(face)
        val fb = bounds(face); val cx = fb.centerX(); val cy = fb.centerY()
        // expand: wider, and much taller upward so the forehead up to the hairline is covered
        val exp = FloatArray(face.size)
        var i = 0; while (i + 1 < face.size) {
            val x = face[i]; val y = face[i + 1]; val up = if (y < cy) 1.42f else 1.06f
            exp[i] = cx + (x - cx) * 1.14f; exp[i + 1] = cy + (y - cy) * up; i += 2
        }
        val pw = photo.width; val ph = photo.height
        val mask = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(pathOf(exp, true), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; maskFilter = BlurMaskFilter((fb.width() * 0.06f).coerceAtLeast(2f), BlurMaskFilter.Blur.NORMAL)
        })
        val mpx = IntArray(pw * ph); mask.getPixels(mpx, 0, pw, 0, 0, pw, ph); mask.recycle()
        ImageUtils.applyFaceSkin(photo, mpx, if (foundationOn) foundationIntensity else 0f, if (powderOn) powderIntensity else 0f)
    }

    /** Draw the enabled makeup items onto the final photo, mapping source contours through [m]. */
    private fun drawMakeup(c: Canvas, m: Matrix) {
        val d = fc ?: return
        if (!anyMakeup) return
        fun map(src: FloatArray): FloatArray { val a = src.copyOf(); if (a.isNotEmpty()) m.mapPoints(a); return a }
        val face = map(d.faceOval)
        if (face.isEmpty()) return
        val fb = bounds(face); val faceW = (fb.width()).coerceAtLeast(1f)
        val blur = faceW * 0.012f
        fun centroid(a: FloatArray): FloatArray {
            var sx = 0.0; var sy = 0.0; var n = 0; var i = 0
            while (i + 1 < a.size) { sx += a[i]; sy += a[i + 1]; n++; i += 2 }
            return if (n == 0) floatArrayOf(0f, 0f) else floatArrayOf((sx / n).toFloat(), (sy / n).toFloat())
        }
        // transform mapped points about [cen] by width/height scale + offset (face-width units)
        fun xf(a: FloatArray, cen: FloatArray, sx: Float, sy: Float, dx: Float, dy: Float): FloatArray {
            val r = a.copyOf(); val ddx = dx * faceW; val ddy = dy * faceW
            var i = 0; while (i + 1 < r.size) { r[i] = cen[0] + (r[i] - cen[0]) * sx + ddx; r[i + 1] = cen[1] + (r[i + 1] - cen[1]) * sy + ddy; i += 2 }
            return r
        }

        // Blush: cheeks derived from the eye centres (always symmetric) + per-item nudge/size
        if (blushOn) {
            val le = map(d.leftEye); val re = map(d.rightEye)
            if (le.isNotEmpty() && re.isNotEmpty()) {
                val lc = centroid(le); val rc = centroid(re)
                val down = faceW * 0.34f; val r = faceW * 0.16f
                val cheeks = listOf(
                    floatArrayOf(lc[0] - faceW * 0.02f + blushX * faceW, lc[1] + down + blushY * faceW),
                    floatArrayOf(rc[0] + faceW * 0.02f + blushX * faceW, rc[1] + down + blushY * faceW)
                )
                cheeks.forEach { ck ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        blendMode = BlendMode.OVERLAY
                        shader = RadialGradient(0f, 0f, r,
                            intArrayOf(colorWithAlpha(blushColor, (blushIntensity / 100f * 160).toInt()), colorWithAlpha(blushColor, 0)),
                            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    }
                    c.save(); c.translate(ck[0], ck[1]); c.scale(blushW, blushH)
                    c.drawCircle(0f, 0f, r, paint); c.restore()
                }
            }
        }
        // Eyebrows (per-brow centre, brows nudge/size)
        if (browOn) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(74, 52, 40); blendMode = BlendMode.MULTIPLY
                alpha = (browIntensity / 100f * 150).toInt(); maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
            listOf(d.leftBrowTop to d.leftBrowBottom, d.rightBrowTop to d.rightBrowBottom).forEach { (t, b) ->
                val mt = map(t); val mb = map(b); val cen = centroid(mt + mb)
                c.drawPath(bandPath(xf(mt, cen, browsW, browsH, browsX, browsY), xf(mb, cen, browsW, browsH, browsX, browsY)), p)
            }
        }
        // Lipstick (single lip centre so both lips move together)
        if (lipOn) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = lipColor; blendMode = BlendMode.MULTIPLY
                alpha = (lipIntensity / 100f * 170).toInt(); maskFilter = BlurMaskFilter(blur * 0.7f, BlurMaskFilter.Blur.NORMAL)
            }
            val ult = map(d.upperLipTop); val ulb = map(d.upperLipBottom); val llt = map(d.lowerLipTop); val llb = map(d.lowerLipBottom)
            val cen = centroid(ult + ulb + llt + llb)
            fun T(a: FloatArray) = xf(a, cen, lipsW, lipsH, lipsX, lipsY)
            c.drawPath(bandPath(T(ult), T(ulb)), p)
            c.drawPath(bandPath(T(llt), T(llb)), p)
        }
        // Eyes: eyeliner + lashes, per-eye centre, eyes nudge/size
        if (eyelinerOn || lashesOn) {
            listOf(d.leftEye, d.rightEye).forEach { eyeSrc ->
                val me = map(eyeSrc); if (me.isEmpty()) return@forEach
                val eye = xf(me, centroid(me), eyesW, eyesH, eyesX, eyesY)
                if (eyelinerOn && eyelinerTop) drawEyeliner(c, eye, faceW, true)
                if (eyelinerOn && eyelinerBottom) drawEyeliner(c, eye, faceW, false)
                if (lashesOn) drawLashes(c, eye, faceW)
            }
        }
    }

    /** Eyelashes: fine individual hairs rising above the upper lid (distinct from the eyeliner line). */
    private fun drawLashes(c: Canvas, eye: FloatArray, faceW: Float) {
        if (eye.size < 6) return
        var sx = 0f; var sy = 0f; var n = 0; var i = 0
        while (i + 1 < eye.size) { sx += eye[i]; sy += eye[i + 1]; n++; i += 2 }
        val cx = sx / n; val cy = sy / n
        val top = ArrayList<FloatArray>()
        i = 0; while (i + 1 < eye.size) { if (eye[i + 1] <= cy) top.add(floatArrayOf(eye[i], eye[i + 1])); i += 2 }
        if (top.size < 2) return
        top.sortBy { it[0] }
        val minX = top.first()[0]; val maxX = top.last()[0]; val eyeW = (maxX - minX).coerceAtLeast(1f)
        val a = (lashesIntensity / 100f).coerceIn(0f, 1f)
        val col = Color.argb((a * 220).toInt().coerceIn(0, 255), 12, 9, 9)
        fun sample(u: Float): FloatArray {
            val fx = minX + u * (maxX - minX)
            var k = 0; while (k < top.size - 2 && top[k + 1][0] < fx) k++
            val p0 = top[k]; val p1 = top[k + 1]; val seg = p1[0] - p0[0]
            val t = if (seg != 0f) ((fx - p0[0]) / seg).coerceIn(0f, 1f) else 0f
            return floatArrayOf(p0[0] + t * (p1[0] - p0[0]), p0[1] + t * (p1[1] - p0[1]))
        }
        val count = (eyeW / (faceW * 0.022f)).toInt().coerceIn(8, 22)         // fewer, more distinct lashes
        for (li in 0 until count) {
            val u = (li + 0.5f) / count
            val base = sample(u)
            val distC = ((base[0] - cx) / (eyeW * 0.5f)).coerceIn(-1f, 1f)   // -1 inner .. +1 outer
            val outward = if (base[0] < cx) -1f else 1f
            val lashLen = faceW * (0.03f + 0.045f * kotlin.math.abs(distC))    // longer, visible
            val tipX = base[0] + outward * lashLen * 0.7f                      // strong outward sweep (curl)
            val tipY = base[1] - lashLen * 0.9f                                // rises up
            val ctrlX = base[0] + outward * lashLen * 0.12f
            val ctrlY = base[1] - lashLen * 0.7f
            val path = Path(); path.moveTo(base[0], base[1]); path.quadTo(ctrlX, ctrlY, tipX, tipY)
            // taper: thicker at the root, thin at the tip (two overlaid strokes)
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                strokeWidth = (faceW * 0.0038f).coerceAtLeast(1.2f)
            })
        }
    }

    private fun pathOf(a: FloatArray, close: Boolean): Path {
        val p = Path(); if (a.size < 2) return p
        p.moveTo(a[0], a[1]); var i = 2; while (i + 1 < a.size) { p.lineTo(a[i], a[i + 1]); i += 2 }; if (close) p.close(); return p
    }
    private fun colorWithAlpha(color: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private fun drawEyeliner(c: Canvas, eye: FloatArray, faceW: Float, upper: Boolean) {
        if (eye.size < 6) return
        var sy = 0f; var n = 0; var i = 1; while (i < eye.size) { sy += eye[i]; n++; i += 2 }
        val cy = sy / n
        data class P(val x: Float, val y: Float)
        val pts = ArrayList<P>(); i = 0
        while (i + 1 < eye.size) { val yy = eye[i + 1]; if ((upper && yy <= cy) || (!upper && yy > cy)) pts.add(P(eye[i], yy)); i += 2 }
        if (pts.size < 2) return
        pts.sortBy { it.x }
        val path = Path(); path.moveTo(pts[0].x, pts[0].y); for (k in 1 until pts.size) path.lineTo(pts[k].x, pts[k].y)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((eyelinerIntensity / 100f * 235).toInt().coerceIn(0, 255), 18, 14, 12)
            style = Paint.Style.STROKE; strokeWidth = (faceW * (if (upper) 0.012f else 0.008f)).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter((faceW * 0.004f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(path, p)
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

        // The photo bitmap is ALWAYS pw x ph with a transform that does not depend on the strip,
        // so toggling the strip never moves or resizes the photo.
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
        ImageUtils.autoWhiteBalance(photo, whiteBalance)
        ImageUtils.applySkinEffects(photo, skinSmooth, blemish, skinLighten)
        ImageUtils.applySharpen(photo, sharpen)
        applyFaceSkinMakeup(photo, m)
        drawMakeup(pc, m)
        drawClothing(pc, pw, ph)

        // Output is ALWAYS pw x ph. The strip is a band over the bottom of the photo, so changing
        // the strip never alters the photo's width or the overall size - only its own height.
        val out = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawColor(Color.WHITE)
        oc.drawBitmap(photo, 0f, 0f, null)
        photo.recycle()
        if (sh > 0) drawStrip(oc, pw, ph - sh, sh)
        drawBorder(oc, pw, ph)
        return out
    }

    private fun drawBorder(c: Canvas, w: Int, h: Int) {
        if (!borderOn) return
        val t = (h * (borderMm / preset.h)).coerceAtLeast(2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = borderColor; strokeWidth = t
        }
        c.drawRect(t / 2f, t / 2f, w - t / 2f, h - t / 2f, p)
    }

    /** Composites a photoreal attire PNG over the lower (neck/shoulder) part of the photo. */
    private fun drawClothing(c: Canvas, pw: Int, ph: Int) {
        val path = attireAssets.getOrNull(attireIndex) ?: return
        val ov = attireBitmap(path) ?: return
        val w = pw * clothingSize
        val h = w * ov.height / ov.width
        val left = pw / 2f - w / 2f + clothingX * pw
        val top = ph * clothingPos
        val dst = RectF(left, top, left + w, top + h)
        c.drawBitmap(ov, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun drawStrip(c: Canvas, pw: Int, top: Int, sh: Int) {
        val lines = stripLines.filter { it.on && it.text.isNotBlank() }
        val pad = sh * 0.14f
        val avail = sh - 2 * pad
        val lineH = if (lines.isEmpty()) avail else avail / lines.size
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stripFg; textAlign = Paint.Align.CENTER; typeface = stripTypeface() }

        // font size by height (scaled by user), then shrink so the text fits the available width
        val maxTextW = if (stripBgMode == StripBgMode.TEXT) pw * 0.92f else pw * stripWidthFrac * 0.92f
        var fontSize = (lineH * 0.8f).coerceAtMost(sh * 0.55f) * stripFontScale
        textPaint.textSize = fontSize
        var longest = if (lines.isEmpty()) 0f else lines.maxOf { textPaint.measureText(it.text) }
        if (longest > maxTextW && longest > 0f) {
            fontSize *= maxTextW / longest
            textPaint.textSize = fontSize.coerceAtLeast(6f)
            longest = lines.maxOf { textPaint.measureText(it.text) }
        }

        // background rectangle: full width (x width%), or hugging the text
        val bgW = if (stripBgMode == StripBgMode.TEXT) (longest + textPaint.textSize * 1.1f).coerceIn(0f, pw.toFloat()) else pw * stripWidthFrac
        val sx = (pw - bgW) / 2f
        val rect = RectF(sx, top.toFloat(), sx + bgW, (top + sh).toFloat())
        c.drawRect(rect, Paint().apply { color = stripBg })
        if (stripBorderOn) {
            val t = (sh * (stripBorderMm / stripH)).coerceAtLeast(2f)
            c.drawRect(
                rect.left + t / 2, rect.top + t / 2, rect.right - t / 2, rect.bottom - t / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = stripBorderColor; strokeWidth = t }
            )
        }
        lines.forEachIndexed { i, ln ->
            val cy = top + pad + lineH * (i + 0.5f)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            c.drawText(ln.text, pw / 2f, baseline, textPaint)
        }
    }

    private var rendering = false
    private var pendingRender = false
    private fun refresh() {
        if (src == null) return
        if (rendering) { pendingRender = true; return }
        rendering = true
        viewModelScope.launch {
            try {
                do {
                    pendingRender = false
                    val bmp = withContext(Dispatchers.Default) { buildFinal(previewHeightPx) }
                    if (bmp != null) preview = bmp
                } while (pendingRender)
            } finally {
                rendering = false
            }
        }
    }

    /** generic setter used by sliders so each change re-renders the preview */
    fun onParamChanged() = refresh()

    private var refineJob: Job? = null
    /** Recompute the displayed matte: hair-edge colour decontamination (defringe) to kill the halo. */
    fun applyRefine() {
        val raw = fgRaw ?: return
        refineJob?.cancel()
        refineJob = viewModelScope.launch {
            delay(120)   // coalesce rapid slider drags so passes don't stack up and OOM
            val refined = withContext(Dispatchers.Default) { ImageUtils.estimateForeground(raw, defringeStrength) }
            fg = refined
            refresh()
        }
    }

    fun openExportPreview() {
        if (src == null) return
        viewModelScope.launch {
            busy = true; busyMsg = "Rendering export preview..."
            try {
                val b = withContext(Dispatchers.Default) { buildOutput().first }
                exportPreview = b
            } catch (e: Exception) {
                message = "Preview failed: ${e.message}"
            } finally { busy = false }
        }
    }

    fun closeExportPreview() { exportPreview = null }

    // ---------------------------------------------------------- sheet
    fun fitCount(): Triple<Int, Int, Int> {
        val PW = ImageUtils.mmToPx(page.w)
        val PH = ImageUtils.mmToPx(page.h)
        val cw = ImageUtils.mmToPx(preset.w)
        val ch = ImageUtils.mmToPx(preset.h)
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

    /** The thing we export/share: either one photo or the full tiled page, with its size in mm. */
    private fun buildOutput(): Triple<Bitmap, Float, Float> =
        if (exportSingle) {
            val b = buildFinal(ImageUtils.mmToPx(preset.h))!!
            Triple(b, preset.w, preset.h)
        } else {
            Triple(buildSheet(), page.w, page.h)
        }

    // ---------------------------------------------------------- export
    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()

    fun exportJpg() {
        if (src == null) return
        viewModelScope.launch {
            busy = true; busyMsg = "Building JPG sheet..."
            try {
                val uri = withContext(Dispatchers.Default) {
                    val sheet = buildOutput().first
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
                    val (sheet, wMm, hMm) = buildOutput()
                    val wPt = (wMm / 25.4f * 72f).toInt()
                    val hPt = (hMm / 25.4f * 72f).toInt()
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
                val sheet = withContext(Dispatchers.Default) { buildOutput().first }
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

    fun shareJpg() = shareOutput(false)
    fun sharePdf() = shareOutput(true)

    private fun shareOutput(isPdf: Boolean) {
        if (src == null) return
        viewModelScope.launch {
            busy = true; busyMsg = "Preparing to share..."
            try {
                val ctx = getApplication<Application>()
                val file = withContext(Dispatchers.Default) {
                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                    val (bmp, wMm, hMm) = buildOutput()
                    if (isPdf) {
                        val f = File(dir, "passport_${System.currentTimeMillis()}.pdf")
                        val wPt = (wMm / 25.4f * 72f).toInt()
                        val hPt = (hMm / 25.4f * 72f).toInt()
                        val doc = PdfDocument()
                        val pg = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, 1).create())
                        pg.canvas.drawBitmap(
                            bmp, Rect(0, 0, bmp.width, bmp.height), Rect(0, 0, wPt, hPt),
                            Paint(Paint.FILTER_BITMAP_FLAG)
                        )
                        doc.finishPage(pg)
                        FileOutputStream(f).use { doc.writeTo(it) }
                        doc.close(); bmp.recycle(); f
                    } else {
                        val f = File(dir, "passport_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        bmp.recycle(); f
                    }
                }
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (isPdf) "application/pdf" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share via")
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ctx.startActivity(chooser)
            } catch (e: Exception) {
                message = "Share failed: ${e.message}"
            } finally { busy = false }
        }
    }
}
