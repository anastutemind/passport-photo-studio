package com.aps.passportphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    /**
     * Refine a segmentation matte so hair/edges blend on any background. Pure per-pixel O(n)
     * (no neighbourhood erosion, so it cannot run out of memory on full-resolution mattes):
     * trims the soft halo by cutting low alpha, hardens the transition, and decontaminates the
     * remaining semi-transparent edge colour. [amount] ~0..6.
     */
    fun refineMatte(src: Bitmap, amount: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (amount <= 0f) return out
        val w = out.width; val h = out.height; val n = w * h
        val px = IntArray(n); out.getPixels(px, 0, w, 0, 0, w, h)
        val cut = (0.12f + amount * 0.07f).coerceAtMost(0.75f)
        for (i in 0 until n) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            if (a == 0 || a == 255) continue
            val tt = (((a / 255f) - cut) / (1f - cut)).coerceIn(0f, 1f)
            val na = (tt * tt * (3f - 2f * tt) * 255f).toInt().coerceIn(0, 255)
            var cr = (p shr 16) and 0xFF; var cg = (p shr 8) and 0xFF; var cb = p and 0xFF
            if (na in 1..235) { cr = (cr * 0.85f).toInt(); cg = (cg * 0.85f).toInt(); cb = (cb * 0.85f).toInt() }
            px[i] = (na shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Build a cut-out that keeps [src]'s real colours everywhere, taking only the alpha from
     * [alphaSrc] (same size). Needed so a later Restore/Hair brush reveals the photo, not black
     * (ML Kit's foreground bitmap stores black RGB behind the subject).
     */
    fun alphaFrom(src: Bitmap, alphaSrc: Bitmap): Bitmap {
        val w = src.width; val h = src.height; val n = w * h
        val sp = IntArray(n); src.getPixels(sp, 0, w, 0, 0, w, h)
        val ap = IntArray(n)
        if (alphaSrc.width == w && alphaSrc.height == h) alphaSrc.getPixels(ap, 0, w, 0, 0, w, h)
        else {
            val scaled = Bitmap.createScaledBitmap(alphaSrc, w, h, true)
            scaled.getPixels(ap, 0, w, 0, 0, w, h); if (scaled != alphaSrc) scaled.recycle()
        }
        val out = IntArray(n)
        for (i in 0 until n) out[i] = (ap[i] and 0xFF000000.toInt()) or (sp[i] and 0x00FFFFFF)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) }
    }

    /**
     * Hair-edge **colour decontamination (defringe)**: semi-transparent matte pixels still carry the
     * ORIGINAL background colour, which shows as a light halo on a dark background. Replaces the edge
     * colour with the true foreground colour, estimated as the alpha-weighted blurred colour of the
     * surrounding (mostly opaque) pixels - soft alpha kept, halo colour gone. Memory-light (reuses the
     * Int box blur) and guarded against OOM. [amount] ~0..6. Returns a new bitmap.
     */
    private fun boxBlurFloat(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        if (r < 1) return src.copyOf()
        val tmp = FloatArray(src.size); val out = FloatArray(src.size); val d = (2 * r + 1).toFloat()
        for (y in 0 until h) { val row = y * w; var s = 0f
            for (k in -r..r) s += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) { tmp[row + x] = s / d; s += src[row + (x + r + 1).coerceIn(0, w - 1)] - src[row + (x - r).coerceIn(0, w - 1)] } }
        for (x in 0 until w) { var s = 0f
            for (k in -r..r) s += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) { out[y * w + x] = s / d; s += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] - tmp[(y - r).coerceIn(0, h - 1) * w + x] } }
        return out
    }

    /**
     * **Foreground colour estimation** (Fast Multi-Level, coarse approximation - PyMatting / PhotoRoom
     * technique). Estimates the TRUE foreground colour as the alpha-weighted neighbourhood colour at a
     * coarse scale, then blends it into the soft edge (more where alpha is low). Removes the hair halo
     * correctly on ANY background colour, keeping real hair colour instead of darkening/erasing it.
     * [strength] 0..1. Memory-light (coarse buffers). Returns a new bitmap.
     */
    fun estimateForeground(src: Bitmap, strength: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (strength <= 0f) return out
        val w = out.width; val h = out.height; val n = w * h
        val px = IntArray(n); out.getPixels(px, 0, w, 0, 0, w, h)
        val K = (minOf(w, h) / 150).coerceIn(4, 16)
        val cw = (w + K - 1) / K; val ch = (h + K - 1) / K; val cn = cw * ch
        val sr = FloatArray(cn); val sg = FloatArray(cn); val sb = FloatArray(cn); val sa = FloatArray(cn)
        for (i in 0 until n) {
            val p = px[i]; val a = ((p ushr 24) and 0xFF) / 255f
            val cell = (i % w) / K + (i / w) / K * cw
            sr[cell] += ((p shr 16) and 0xFF) * a; sg[cell] += ((p shr 8) and 0xFF) * a; sb[cell] += (p and 0xFF) * a; sa[cell] += a
        }
        // blur the coarse premult + alpha so empty (background) cells get filled with nearby fg colour
        val br = boxBlurFloat(sr, cw, ch, 2); val bg = boxBlurFloat(sg, cw, ch, 2); val bb = boxBlurFloat(sb, cw, ch, 2); val ba = boxBlurFloat(sa, cw, ch, 2)
        val fr = FloatArray(cn); val fg = FloatArray(cn); val fb = FloatArray(cn)
        for (c in 0 until cn) { val dd = ba[c].coerceAtLeast(0.0015f); fr[c] = br[c] / dd; fg[c] = bg[c] / dd; fb[c] = bb[c] / dd }
        val st = strength.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val p = px[i]; val a = (p ushr 24) and 0xFF; if (a == 0) continue
            val af = a / 255f
            val x = i % w; val y = i / w
            // bilinear sample of the coarse foreground at this pixel
            val gx = (x.toFloat() / K - 0.5f).coerceIn(0f, cw - 1.001f); val gy = (y.toFloat() / K - 0.5f).coerceIn(0f, ch - 1.001f)
            val x0 = gx.toInt(); val y0 = gy.toInt(); val tx = gx - x0; val ty = gy - y0
            val i00 = y0 * cw + x0; val i10 = i00 + 1; val i01 = i00 + cw; val i11 = i01 + 1
            fun bl(arr: FloatArray) = (arr[i00] * (1 - tx) + arr[i10] * tx) * (1 - ty) + (arr[i01] * (1 - tx) + arr[i11] * tx) * ty
            val fR = bl(fr); val fG = bl(fg); val fB = bl(fb)
            val k = (1f - af) * st
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val nr = (r + (fR - r) * k).toInt().coerceIn(0, 255)
            val ng = (g + (fG - g) * k).toInt().coerceIn(0, 255)
            val nb = (b + (fB - b) * k).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun decontaminateHalo(src: Bitmap, tight: Float, dark: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (tight <= 0f && dark <= 0f) return out
        val w = out.width; val h = out.height; val n = w * h
        val px = IntArray(n); out.getPixels(px, 0, w, 0, 0, w, h)        // single pass, no big buffers -> no OOM
        val cutLow = (0.05f + tight.coerceIn(0f, 1f) * 0.6f).coerceIn(0f, 0.92f)  // wide range for real control
        val inv = 1f / (1f - cutLow)
        val darkK = dark.coerceIn(0f, 1f)
        val tg = tight.coerceIn(0f, 1f)
        for (i in 0 until n) {
            val p = px[i]; val a = (p ushr 24) and 0xFF
            if (a == 0) continue
            val af = a / 255f
            val r0 = (p shr 16) and 0xFF; val g0 = (p shr 8) and 0xFF; val b0 = p and 0xFF
            val luma = r0 * 0.30f + g0 * 0.59f + b0 * 0.11f
            // CHOKE the soft matte so the broad grey glow narrows (smoothstep remap of alpha)
            val t = ((af - cutLow) * inv).coerceIn(0f, 1f)
            var na = (t * t * (3f - 2f * t) * 255f).toInt().coerceIn(0, 255)
            // bright, non-skin fringe = the hair halo (opaque OR soft)
            val bright = ((luma - 95f) / 160f).coerceIn(0f, 1f)
            val halo = luma > 95f && !isSkin(r0, g0, b0)
            if (halo) na = (na * (1f - tg * bright)).toInt().coerceIn(0, 255)   // fade it transparent
            // darken what's left: the whole halo (opaque included), or just the soft edge elsewhere
            val dgate = if (halo) bright else bright * (1f - af)
            val dk = 1f - darkK * dgate * 0.85f
            val r = (r0 * dk).toInt().coerceIn(0, 255); val g = (g0 * dk).toInt().coerceIn(0, 255); val b = (b0 * dk).toInt().coerceIn(0, 255)
            px[i] = (na shl 24) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
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

    private fun isSkin(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        if (mx < 70 || mx > 250) return false     // too dark (hair/shadow) or blown out
        if (mx - mn < 12) return false            // greyish: eye whites, neutral background
        if (!(r > g && g >= b)) return false       // skin tends to R >= G >= B
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return cb in 80f..128f && cr in 136f..176f
    }

    private fun pixelsOf(bmp: Bitmap): IntArray {
        val a = IntArray(bmp.width * bmp.height)
        bmp.getPixels(a, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return a
    }

    /**
     * Professional **frequency-separation** skin retouch. The image is split into a low-frequency
     * tonal layer (heavy blur) and the high-frequency texture (pores, fine detail = base - low).
     * Smoothing evens out ONLY the low-frequency layer (blotches, uneven tone) and recombines the
     * ORIGINAL texture on top, so skin stays real instead of going plastic. Applied only on
     * skin-coloured pixels AND only away from strong edges (eyes, brows, lips, nostrils, hair stay
     * crisp). Lighten brightens skin only. Each 0..100. Alpha preserved.
     */
    fun applySkinEffects(bmp: Bitmap, smooth: Float, blemish: Float, lighten: Float) {
        if (smooth <= 0f && blemish <= 0f && lighten <= 0f) return
        val w = bmp.width; val h = bmp.height; val n = w * h
        val base = pixelsOf(bmp)
        val luma = IntArray(n)
        val skin = BooleanArray(n)
        for (i in 0 until n) {
            val p = base[i]; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            luma[i] = (r * 77 + g * 150 + b * 29) shr 8
            skin[i] = isSkin(r, g, b)
        }
        val doSmooth = smooth > 0f || blemish > 0f
        // low-freq radius scales with image size and strength; blemish removal uses a larger radius.
        val baseRad = (minOf(w, h) / 220f).coerceAtLeast(2f)
        val rad = (baseRad * (1f + (maxOf(smooth, blemish) / 100f) * 2.5f)).toInt().coerceIn(2, 18)
        val low = if (doSmooth) boxBlurRGB(base, w, h, rad) else base          // tonal layer
        val low2 = if (doSmooth) boxBlurRGB(low, w, h, rad) else base           // flattened tonal layer
        // how strongly we flatten the tonal layer (texture is always kept intact)
        val strength = ((smooth / 100f) * 0.9f + (blemish / 100f) * 1.0f).coerceAtMost(1f)
        val lAdd = (lighten / 100f) * 40f
        for (i in 0 until n) {
            if (!skin[i]) continue
            val p = base[i]; val al = p and 0xFF000000.toInt()
            var r = (p shr 16) and 0xFF; var g = (p shr 8) and 0xFF; var b = p and 0xFF
            val right = if ((i % w) < w - 1) luma[i + 1] else luma[i]
            val down = if (i < n - w) luma[i + w] else luma[i]
            val grad = abs(luma[i] - right) + abs(luma[i] - down)
            val protect = (grad / 30f).coerceIn(0f, 1f)            // strong edge -> keep sharp
            val blend = strength * (1f - protect)
            if (blend > 0f) {
                // shift ONLY the low-frequency tone toward its flattened version; high-freq texture untouched
                val lo = low[i]; val lo2 = low2[i]
                r += ((((lo2 shr 16) and 0xFF) - ((lo shr 16) and 0xFF)).toFloat() * blend).toInt()
                g += ((((lo2 shr 8) and 0xFF) - ((lo shr 8) and 0xFF)).toFloat() * blend).toInt()
                b += (((lo2 and 0xFF) - (lo and 0xFF)).toFloat() * blend).toInt()
            }
            if (lAdd > 0f) {
                val k = lAdd * (1f - 0.5f * protect)
                r = (r + k).toInt(); g = (g + k).toInt(); b = (b + k).toInt()
            }
            base[i] = al or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        bmp.setPixels(base, 0, w, 0, 0, w, h)
    }

    /**
     * White-patch auto white balance: finds the brightest near-neutral pixels (a white/light
     * background or shirt) and scales the channels so they become truly neutral, removing a colour
     * cast from indoor lighting WITHOUT greying out skin. [amount] 0..100 scales the correction.
     */
    fun autoWhiteBalance(bmp: Bitmap, amount: Float) {
        if (amount <= 0f) return
        val w = bmp.width; val h = bmp.height; val n = w * h
        val px = pixelsOf(bmp)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
        for (i in 0 until n) {
            val p = px[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx > 200 && (mx - mn) < 45) { sr += r; sg += g; sb += b; cnt++ }   // bright + near-neutral
        }
        if (cnt < n / 400) return                      // not enough white reference to trust
        val ar = sr / cnt; val ag = sg / cnt; val ab = sb / cnt
        val target = maxOf(ar, ag, ab)
        val t = (amount / 100f)
        val gr = (1f + ((target / ar).toFloat() - 1f) * t).coerceIn(0.6f, 1.6f)
        val gg = (1f + ((target / ag).toFloat() - 1f) * t).coerceIn(0.6f, 1.6f)
        val gb = (1f + ((target / ab).toFloat() - 1f) * t).coerceIn(0.6f, 1.6f)
        for (i in 0 until n) {
            val p = px[i]; val al = p and 0xFF000000.toInt()
            val r = (((p shr 16) and 0xFF) * gr).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * gg).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * gb).toInt().coerceIn(0, 255)
            px[i] = al or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Separable box blur on RGB (alpha preserved), sliding-window O(n). Smooth, never blocky. */
    private fun boxBlurRGB(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (r < 1) return src.copyOf()
        val tmp = IntArray(src.size); val out = IntArray(src.size)
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val p = src[row + k.coerceIn(0, w - 1)]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            for (x in 0 until w) {
                val p = src[row + x]
                tmp[row + x] = (p and 0xFF000000.toInt()) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val po = src[row + (x - r).coerceIn(0, w - 1)]; val pi = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) { val p = tmp[k.coerceIn(0, h - 1) * w + x]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            for (y in 0 until h) {
                val p = tmp[y * w + x]
                out[y * w + x] = (p and 0xFF000000.toInt()) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val po = tmp[(y - r).coerceIn(0, h - 1) * w + x]; val pi = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((pi shr 16) and 0xFF) - ((po shr 16) and 0xFF)
                sg += ((pi shr 8) and 0xFF) - ((po shr 8) and 0xFF)
                sb += (pi and 0xFF) - (po and 0xFF)
            }
        }
        return out
    }

    /**
     * Foundation + powder applied only on SKIN pixels inside [maskPx] (a soft face-region mask,
     * alpha = coverage). Foundation evens skin tone toward a blurred copy and lifts it slightly;
     * powder tames shine by pulling bright skin down. Hair/brows/eyes/lips are skipped by the skin
     * test, so it never smears into them. Each 0..100.
     */
    fun applyFaceSkin(photo: Bitmap, maskPx: IntArray, foundation: Float, powder: Float) {
        if (foundation <= 0f && powder <= 0f) return
        val w = photo.width; val h = photo.height; val n = w * h
        val base = pixelsOf(photo)
        val rad = (minOf(w, h) / 200f).toInt().coerceIn(2, 10)
        val low = if (foundation > 0f) boxBlurRGB(base, w, h, rad) else base
        val fStr = foundation / 100f; val pStr = powder / 100f
        for (i in 0 until n) {
            val mw = (maskPx[i] ushr 24) and 0xFF; if (mw == 0) continue
            val mwf = mw / 255f
            val p = base[i]; val al = p and 0xFF000000.toInt()
            var r = (p shr 16) and 0xFF; var g = (p shr 8) and 0xFF; var b = p and 0xFF
            if (!isSkin(r, g, b)) continue
            if (foundation > 0f) {
                val s = fStr * 0.85f * mwf; val q = low[i]
                r = (r + (((q shr 16) and 0xFF) - r) * s).toInt()
                g = (g + (((q shr 8) and 0xFF) - g) * s).toInt()
                b = (b + ((q and 0xFF) - b) * s).toInt()
                val br = fStr * 16f * mwf
                r = (r + br).toInt(); g = (g + br).toInt(); b = (b + br).toInt()
            }
            if (powder > 0f) {
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                if (luma > 165) {
                    val cut = ((luma - 165) / 90f).coerceIn(0f, 1f) * pStr * 0.7f * mwf
                    r = (r - (r - 165) * cut).toInt(); g = (g - (g - 165) * cut).toInt(); b = (b - (b - 165) * cut).toInt()
                }
            }
            base[i] = al or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        photo.setPixels(base, 0, w, 0, 0, w, h)
    }

    /** Unsharp-mask sharpening over the whole image, 0..100. Alpha preserved. */
    fun applySharpen(bmp: Bitmap, amount: Float) {
        if (amount <= 0f) return
        val blur = cheapBlur(bmp, 2)
        val base = pixelsOf(bmp); val bl = pixelsOf(blur)
        if (blur != bmp) blur.recycle()
        val k = (amount / 100f) * 1.4f
        for (i in base.indices) {
            val p = base[i]; val al = p and 0xFF000000.toInt()
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val q = bl[i]
            val nr = (r + k * (r - ((q shr 16) and 0xFF))).toInt().coerceIn(0, 255)
            val ng = (g + k * (g - ((q shr 8) and 0xFF))).toInt().coerceIn(0, 255)
            val nb = (b + k * (b - (q and 0xFF))).toInt().coerceIn(0, 255)
            base[i] = al or (nr shl 16) or (ng shl 8) or nb
        }
        bmp.setPixels(base, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }
}
