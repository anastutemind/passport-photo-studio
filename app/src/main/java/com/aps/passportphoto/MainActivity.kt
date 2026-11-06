package com.aps.passportphoto

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.math.roundToInt

private fun darkColors() = darkColorScheme(
    primary = Color(0xFF4F7CFF), onPrimary = Color.White,
    background = Color(0xFF0B0E14), onBackground = Color(0xFFEDEFF4),
    surface = Color(0xFF151A22), onSurface = Color(0xFFEDEFF4),
    surfaceVariant = Color(0xFF1E232D), onSurfaceVariant = Color(0xFF8C95A6),
    outline = Color(0xFF28303B)
)
private fun lightColors() = lightColorScheme(
    primary = Color(0xFF2F6BFF), onPrimary = Color.White,
    background = Color(0xFFF3F5FA), onBackground = Color(0xFF0C1322),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0C1322),
    surfaceVariant = Color(0xFFEDF1F8), onSurfaceVariant = Color(0xFF8A93A6),
    outline = Color(0xFFE4E8F0)
)

private val STEPS = listOf("Align", "Enhance", "Makeup", "Background", "Attire", "Strip", "Export")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var dark by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = if (dark) darkColors() else lightColors()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    EditorScreen(dark = dark, onToggleTheme = { dark = !dark })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel(), dark: Boolean, onToggleTheme: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var step by remember { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.loadUri(uri) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val context = LocalContext.current
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) camUri?.let { vm.loadUri(it) } }
    fun capture() {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val u = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        camUri = u
        camLauncher.launch(u)
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- header ----
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.hasImage) { TextPill("Home") { vm.newSession(); step = 0 }; Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(if (vm.hasImage) STEPS[step] else "Passport Studio", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = cs.onSurface)
                    if (vm.hasImage) Text("Step ${step + 1} of ${STEPS.size}", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                TextPill(if (dark) "Light" else "Dark") { onToggleTheme() }
                if (vm.hasImage) {
                    Spacer(Modifier.width(8.dp))
                    Surface(onClick = { pick() }, shape = RoundedCornerShape(22.dp), color = cs.primary) {
                        Text("Change", color = cs.onPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp), fontSize = 13.sp)
                    }
                }
            }
            if (vm.hasImage) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                STEPS.forEachIndexed { i, _ -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) cs.primary else cs.surfaceVariant)) }
            }

            PreviewFrame(vm)

            if (!vm.hasImage) {
                StartScreen(vm, onTake = { capture() }, onPick = { pick() })
            } else {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (step) {
                        0 -> SectionCard {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallAction("Auto-fit", true, !vm.busy, Modifier.weight(1f)) { vm.autoFitToGuide() }
                                SmallAction("Auto align", false, !vm.busy, Modifier.weight(1f)) { vm.autoAlign() }
                                SmallAction("Reset", false, true, Modifier.weight(1f)) { vm.reset() }
                            }
                            PSlider("Zoom", vm.scale, 0.4f..3f, default = 1f) { vm.scale = it; vm.onParamChanged() }
                            PSlider("Horizontal", vm.offX, -0.5f..0.5f, default = 0f) { vm.offX = it; vm.onParamChanged() }
                            PSlider("Vertical", vm.offY, -0.5f..0.5f, default = 0f) { vm.offY = it; vm.onParamChanged() }
                            PSlider("Rotation", vm.rot, -30f..30f, default = 0f) { vm.rot = it; vm.onParamChanged() }
                        }
                        1 -> EnhanceCard(vm)
                        2 -> MakeupCard(vm)
                        3 -> SectionCard {
                            if (!vm.bgRemoved) {
                                Text("Replace the background with a solid studio color. You can undo this anytime.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                                PillButton("Remove background", Modifier.fillMaxWidth(), enabled = !vm.busy) { vm.removeBackground() }
                            } else {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Background color", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    SmallAction("Restore original", false, !vm.busy) { vm.restoreBackground() }
                                }
                                Swatches(vm.bgColor, listOf(AColor.WHITE, AColor.rgb(43, 95, 176), AColor.rgb(17, 17, 17), AColor.rgb(214, 227, 245), AColor.rgb(232, 232, 232), AColor.rgb(245, 240, 225))) { vm.bgColor = it; vm.onParamChanged() }
                                PillButton("Brush touch-up (fix edges)", Modifier.fillMaxWidth()) { vm.openTouchUp() }
                                Text("Paint to remove leftover background, restore cut-away parts, or recover hair detail.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                Label("Hair edge fix (foreground estimation)")
                                PSlider("Edge cleanup", vm.defringeStrength * 100f, 0f..100f, whole = true, default = 90f) { vm.defringeStrength = it / 100f; vm.applyRefine() }
                                Text("Recovers the true hair colour at the edges so there's no halo on any background. Lower it if hair looks too dark/muddy.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        4 -> SectionCard {
                            ChipRow(vm.attirePresets, vm.attireIndex) { vm.setAttire(it) }
                            if (vm.attireIndex != 0) {
                                PSlider("Horizontal", vm.clothingX, -0.5f..0.5f, default = 0f) { vm.clothingX = it; vm.onParamChanged() }
                                PSlider("Vertical", vm.clothingPos, 0.2f..0.9f, default = 0.55f) { vm.clothingPos = it; vm.onParamChanged() }
                                PSlider("Width", vm.clothingSize, 0.6f..1.8f, default = 1.05f) { vm.clothingSize = it; vm.onParamChanged() }
                            } else Text("Pick a shirt or suit to overlay.", fontSize = 13.sp, color = cs.onSurfaceVariant)
                        }
                        5 -> StripCard(vm)
                        6 -> SectionCard {
                            Label("Export")
                            Segmented(listOf("Full page", "Single photo"), if (vm.exportSingle) 1 else 0) { vm.exportSingle = (it == 1); vm.onParamChanged() }
                            if (!vm.exportSingle) {
                                Label("Page size")
                                ChipRow(EditorViewModel.PAGE_PRESETS.map { it.label }, EditorViewModel.PAGE_PRESETS.indexOf(vm.page)) { vm.page = EditorViewModel.PAGE_PRESETS[it]; vm.onParamChanged() }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PSlider("Margin (mm)", vm.margin, 0f..30f, whole = true, default = 5f, modifier = Modifier.weight(1f)) { vm.margin = it; vm.onParamChanged() }
                                    PSlider("Gap (mm)", vm.gap, 0f..20f, whole = true, default = 2f, modifier = Modifier.weight(1f)) { vm.gap = it; vm.onParamChanged() }
                                }
                                val (count, cols, rows) = vm.fitCount()
                                Text("Fits $count per page  ($cols x $rows)", fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.Medium)
                            }
                            ToggleRow("Cut border (per photo)", vm.borderOn) { vm.borderOn = it; vm.onParamChanged() }
                            if (vm.borderOn) {
                                PSlider("Border thickness (mm)", vm.borderMm, 0.1f..1.5f, default = 0.3f) { vm.borderMm = it; vm.onParamChanged() }
                                Swatches(vm.borderColor, listOf(AColor.rgb(140, 140, 140), AColor.BLACK, AColor.rgb(43, 95, 176))) { vm.borderColor = it; vm.onParamChanged() }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                // ---- bottom primary ----
                Surface(color = cs.surface, shadowElevation = 14.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, cs.outline), colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.onSurface)) { Text("Back", fontWeight = FontWeight.SemiBold) }
                        PillButton(if (step < STEPS.lastIndex) "Next" else "Preview & Export",
                            Modifier.weight(if (step > 0) 1.6f else 1f), enabled = !vm.busy, trailingArrow = step < STEPS.lastIndex) {
                            if (step < STEPS.lastIndex) step++ else vm.openExportPreview()
                        }
                    }
                }
                // ---- about ----
                TextButton(
                    onClick = {
                        val ctx = vm.getApplication<android.app.Application>()
                        ctx.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://anastutemind.github.io/passport-photo-studio-privacy-policy.html")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Privacy policy · Passport Photo Studio 1.0", fontSize = 12.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        if (vm.exportPreview != null) ExportDialog(vm)
        if (vm.touchUpOpen) TouchUpScreen(vm)
        if (vm.busy) Box(Modifier.fillMaxSize().background(Color(0xCC05070B)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = cs.primary)
                Spacer(Modifier.height(16.dp))
                Text(vm.busyMsg, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        }
    }
}

@Composable
private fun EnhanceCard(vm: EditorViewModel) {
    val cs = MaterialTheme.colorScheme
    data class Adj(val name: String, val value: Float, val range: ClosedFloatingPointRange<Float>, val def: Float, val whole: Boolean, val set: (Float) -> Unit)
    val adjs = listOf(
        Adj("Brightness", vm.brightness, 0.5f..1.5f, 1f, false) { vm.brightness = it; vm.onParamChanged() },
        Adj("Contrast", vm.contrast, 0.5f..1.5f, 1f, false) { vm.contrast = it; vm.onParamChanged() },
        Adj("Saturation", vm.saturation, 0f..2f, 1f, false) { vm.saturation = it; vm.onParamChanged() },
        Adj("White bal", vm.whiteBalance, 0f..100f, 0f, true) { vm.whiteBalance = it; vm.onParamChanged() },
        Adj("Smooth", vm.skinSmooth, 0f..100f, 0f, true) { vm.skinSmooth = it; vm.onParamChanged() },
        Adj("Blemish", vm.blemish, 0f..100f, 0f, true) { vm.blemish = it; vm.onParamChanged() },
        Adj("Lighten", vm.skinLighten, 0f..100f, 0f, true) { vm.skinLighten = it; vm.onParamChanged() },
        Adj("Sharpen", vm.sharpen, 0f..100f, 0f, true) { vm.sharpen = it; vm.onParamChanged() },
    )
    var sel by remember { mutableStateOf(0) }
    val a = adjs[sel]
    SectionCard {
        PSlider(a.name, a.value, a.range, whole = a.whole, default = a.def) { a.set(it) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            adjs.forEachIndexed { i, adj ->
                val s = i == sel
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (s) cs.primary else cs.surfaceVariant).clickable { sel = i }
                    .padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(adj.name, color = if (s) cs.onPrimary else cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = if (s) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun MakeupCard(vm: EditorViewModel) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(vm.hasImage) { if (vm.hasImage && !vm.makeupReady) vm.ensureFaceContours() }
    SectionCard {
        Text("Makeup (optional)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = cs.onSurface)
        if (!vm.makeupReady) {
            Text("Detecting the face. If it doesn't appear, make sure the face is clear and tap retry.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            PillButton("Detect face", Modifier.fillMaxWidth(), enabled = !vm.busy) { vm.ensureFaceContours() }
        } else {
            Text("Subtle, natural tints on the detected features. All off by default. Each item has its own Move/Size below it.", fontSize = 12.sp, color = cs.onSurfaceVariant)
            ToggleRow("Lipstick", vm.lipOn) { vm.lipOn = it; vm.onParamChanged() }
            if (vm.lipOn) {
                Swatches(vm.lipColor, listOf(AColor.rgb(176, 42, 66), AColor.rgb(138, 24, 44), AColor.rgb(201, 84, 98), AColor.rgb(150, 40, 70), AColor.rgb(120, 32, 32))) { vm.lipColor = it; vm.onParamChanged() }
                PSlider("Lipstick", vm.lipIntensity, 0f..100f, whole = true, default = 55f) { vm.lipIntensity = it; vm.onParamChanged() }
                AdjustSliders(vm.lipsX, vm.lipsY, vm.lipsW, vm.lipsH, { vm.lipsX = it; vm.onParamChanged() }, { vm.lipsY = it; vm.onParamChanged() }, { vm.lipsW = it; vm.onParamChanged() }, { vm.lipsH = it; vm.onParamChanged() })
            }
            ToggleRow("Eyeliner", vm.eyelinerOn) { vm.eyelinerOn = it; vm.onParamChanged() }
            if (vm.eyelinerOn) {
                val sel = if (vm.eyelinerTop && vm.eyelinerBottom) 2 else if (vm.eyelinerBottom) 1 else 0
                Segmented(listOf("Top", "Bottom", "Both"), sel) {
                    vm.eyelinerTop = it == 0 || it == 2; vm.eyelinerBottom = it == 1 || it == 2; vm.onParamChanged()
                }
                PSlider("Eyeliner", vm.eyelinerIntensity, 0f..100f, whole = true, default = 60f) { vm.eyelinerIntensity = it; vm.onParamChanged() }
            }
            ToggleRow("Eyelashes", vm.lashesOn) { vm.lashesOn = it; vm.onParamChanged() }
            if (vm.lashesOn) PSlider("Eyelashes", vm.lashesIntensity, 0f..100f, whole = true, default = 55f) { vm.lashesIntensity = it; vm.onParamChanged() }
            if (vm.eyelinerOn || vm.lashesOn) {
                Label("Eyes - placement & size")
                AdjustSliders(vm.eyesX, vm.eyesY, vm.eyesW, vm.eyesH, { vm.eyesX = it; vm.onParamChanged() }, { vm.eyesY = it; vm.onParamChanged() }, { vm.eyesW = it; vm.onParamChanged() }, { vm.eyesH = it; vm.onParamChanged() })
            }
            ToggleRow("Eyebrow", vm.browOn) { vm.browOn = it; vm.onParamChanged() }
            if (vm.browOn) {
                PSlider("Eyebrow", vm.browIntensity, 0f..100f, whole = true, default = 45f) { vm.browIntensity = it; vm.onParamChanged() }
                AdjustSliders(vm.browsX, vm.browsY, vm.browsW, vm.browsH, { vm.browsX = it; vm.onParamChanged() }, { vm.browsY = it; vm.onParamChanged() }, { vm.browsW = it; vm.onParamChanged() }, { vm.browsH = it; vm.onParamChanged() })
            }
            ToggleRow("Blusher", vm.blushOn) { vm.blushOn = it; vm.onParamChanged() }
            if (vm.blushOn) {
                Swatches(vm.blushColor, listOf(AColor.rgb(226, 122, 122), AColor.rgb(236, 150, 150), AColor.rgb(210, 100, 110), AColor.rgb(240, 168, 140), AColor.rgb(214, 120, 150))) { vm.blushColor = it; vm.onParamChanged() }
                PSlider("Blush", vm.blushIntensity, 0f..100f, whole = true, default = 40f) { vm.blushIntensity = it; vm.onParamChanged() }
                AdjustSliders(vm.blushX, vm.blushY, vm.blushW, vm.blushH, { vm.blushX = it; vm.onParamChanged() }, { vm.blushY = it; vm.onParamChanged() }, { vm.blushW = it; vm.onParamChanged() }, { vm.blushH = it; vm.onParamChanged() })
            }
            ToggleRow("Foundation", vm.foundationOn) { vm.foundationOn = it; vm.onParamChanged() }
            if (vm.foundationOn) PSlider("Foundation", vm.foundationIntensity, 0f..100f, whole = true, default = 40f) { vm.foundationIntensity = it; vm.onParamChanged() }
            ToggleRow("Powder (matte)", vm.powderOn) { vm.powderOn = it; vm.onParamChanged() }
            if (vm.powderOn) PSlider("Powder", vm.powderIntensity, 0f..100f, whole = true, default = 40f) { vm.powderIntensity = it; vm.onParamChanged() }
        }
    }
}

@Composable
private fun AdjustSliders(x: Float, y: Float, w: Float, h: Float, onX: (Float) -> Unit, onY: (Float) -> Unit, onW: (Float) -> Unit, onH: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PSlider("Move X", x, -0.25f..0.25f, default = 0f, modifier = Modifier.weight(1f)) { onX(it) }
        PSlider("Move Y", y, -0.25f..0.25f, default = 0f, modifier = Modifier.weight(1f)) { onY(it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PSlider("Width", w, 0.6f..1.6f, default = 1f, modifier = Modifier.weight(1f)) { onW(it) }
        PSlider("Height", h, 0.6f..1.6f, default = 1f, modifier = Modifier.weight(1f)) { onH(it) }
    }
}

@Composable
private fun StripCard(vm: EditorViewModel) {
    val cs = MaterialTheme.colorScheme
    SectionCard {
        ToggleRow("Show strip", vm.stripOn) { vm.stripOn = it; vm.onParamChanged() }
        if (vm.stripOn) {
            Label("Background")
            Segmented(listOf("Full width", "Text area"), if (vm.stripBgMode == StripBgMode.TEXT) 1 else 0) {
                vm.stripBgMode = if (it == 1) StripBgMode.TEXT else StripBgMode.FULL; vm.onParamChanged()
            }
            if (vm.stripBgMode == StripBgMode.FULL) PSlider("Width %", vm.stripWidthFrac * 100f, 30f..100f, whole = true, default = 100f) { vm.stripWidthFrac = it / 100f; vm.onParamChanged() }
            PSlider("Height (mm)", vm.stripH, 3f..30f, whole = true, default = 8f) { vm.stripH = it; vm.onParamChanged() }
            Label("Font")
            ChipRow(vm.stripFonts, vm.stripFontIndex) { vm.stripFontIndex = it; vm.onParamChanged() }
            ToggleRow("Bold", vm.stripBold) { vm.stripBold = it; vm.onParamChanged() }
            PSlider("Font size", vm.stripFontScale, 0.5f..2f, default = 1f) { vm.stripFontScale = it; vm.onParamChanged() }
            ToggleRow("Strip border", vm.stripBorderOn) { vm.stripBorderOn = it; vm.onParamChanged() }
            if (vm.stripBorderOn) {
                PSlider("Border thickness (mm)", vm.stripBorderMm, 0.1f..1.5f, default = 0.3f) { vm.stripBorderMm = it; vm.onParamChanged() }
                Swatches(vm.stripBorderColor, listOf(AColor.BLACK, AColor.rgb(140, 140, 140), AColor.rgb(43, 95, 176))) { vm.stripBorderColor = it; vm.onParamChanged() }
            }
            Label("Strip color / text color")
            Swatches(vm.stripBg, listOf(AColor.WHITE, AColor.rgb(17, 17, 17), AColor.rgb(43, 95, 176), AColor.rgb(232, 232, 232))) { vm.stripBg = it; vm.onParamChanged() }
            Swatches(vm.stripFg, listOf(AColor.BLACK, AColor.WHITE, AColor.rgb(43, 95, 176), AColor.rgb(176, 0, 0))) { vm.stripFg = it; vm.onParamChanged() }
            Spacer(Modifier.height(2.dp))
            val hints = listOf("Candidate name", "Date of birth", "Date", "Extra line", "Extra line", "Extra line")
            vm.stripLines.forEachIndexed { i, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = line.on, onCheckedChange = { line.on = it; vm.onParamChanged() }, colors = CheckboxDefaults.colors(checkedColor = cs.primary))
                    OutlinedTextField(value = line.text, onValueChange = { line.text = it; vm.onParamChanged() },
                        placeholder = { Text(hints.getOrElse(i) { "Line ${i + 1}" }, color = cs.onSurfaceVariant) },
                        singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(), shape = RoundedCornerShape(10.dp))
                }
                Spacer(Modifier.height(4.dp))
            }
            TextButton(onClick = { vm.stripLines.add(StripLine(true, "")); vm.onParamChanged() }) { Text("+ Add line", color = cs.primary) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewFrame(vm: EditorViewModel) {
    val cs = MaterialTheme.colorScheme
    val bmp = vm.preview
    // NOTE: not keyed on bmp, so zoom/pan survive live preview updates (e.g. toggling makeup).
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val labelArgb = cs.onSurfaceVariant.toArgb()
    val guideBlue = Color(0xFF2F6BFF)

    Box(Modifier.fillMaxWidth().height(if (bmp == null) 200.dp else 300.dp).padding(horizontal = 14.dp, vertical = 8.dp)
        .clip(RoundedCornerShape(20.dp)).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp == null) {
            Text("No photo selected", color = cs.onSurfaceVariant)
        } else {
            Box(Modifier.fillMaxSize()
                .pointerInput(bmp) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset = if (scale <= 1f) Offset.Zero else offset + pan } }
                .pointerInput(bmp) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f }) }) {

                Image(bmp.asImageBitmap(), "preview", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(38.dp).graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })

                if (vm.showGuides && scale < 1.02f) Canvas(Modifier.fillMaxSize()) {
                    val inset = 38.dp.toPx()
                    val aspect = vm.preset.w / vm.preset.h
                    val availW = size.width - 2 * inset; val availH = size.height - 2 * inset
                    val rw: Float; val rh: Float
                    if (availW / availH > aspect) { rh = availH; rw = availH * aspect } else { rw = availW; rh = availW / aspect }
                    val l = (size.width - rw) / 2f; val t = (size.height - rh) / 2f; val r = l + rw; val b = t + rh
                    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 9f))
                    val usable = if (vm.stripOn) (1f - vm.stripH / vm.preset.h).coerceIn(0.4f, 1f) else 1f
                    if (usable < 1f) drawRect(guideBlue.copy(alpha = 0.07f), topLeft = Offset(l, t + usable * rh), size = Size(rw, rh - usable * rh))
                    val headY = t + vm.preset.headTop * usable * rh
                    val chinY = t + vm.preset.chin * usable * rh
                    drawLine(guideBlue, Offset(l, headY), Offset(r, headY), 2f, pathEffect = dash)
                    drawLine(guideBlue, Offset(l, chinY), Offset(r, chinY), 2f, pathEffect = dash)
                    val faceH = chinY - headY
                    val ew = (faceH * 0.72f).coerceAtMost(rw * 0.92f)
                    drawOval(guideBlue, topLeft = Offset(size.width / 2f - ew / 2f, headY), size = Size(ew, faceH), style = Stroke(2f, pathEffect = dash))
                    val len = rw * 0.12f; val sw = 3.dp.toPx()
                    drawLine(guideBlue, Offset(l, t), Offset(l + len, t), sw); drawLine(guideBlue, Offset(l, t), Offset(l, t + len), sw)
                    drawLine(guideBlue, Offset(r, t), Offset(r - len, t), sw); drawLine(guideBlue, Offset(r, t), Offset(r, t + len), sw)
                    drawLine(guideBlue, Offset(l, b), Offset(l + len, b), sw); drawLine(guideBlue, Offset(l, b), Offset(l, b - len), sw)
                    drawLine(guideBlue, Offset(r, b), Offset(r - len, b), sw); drawLine(guideBlue, Offset(r, b), Offset(r, b - len), sw)
                    val wpx = (vm.preset.w / 25.4f * 300f).toInt(); val hpx = (vm.preset.h / 25.4f * 300f).toInt()
                    val tp = android.graphics.Paint().apply { color = labelArgb; textSize = 9.dp.toPx(); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
                    val bp = android.graphics.Paint().apply { color = guideBlue.toArgb(); textSize = 9.dp.toPx(); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true }
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        nc.drawText("${wpx}px", (l + r) / 2f, t - 12f, tp)
                        nc.drawText("${vm.preset.w.toInt()}mm", (l + r) / 2f, b + 26f, tp)
                        nc.save(); nc.rotate(-90f, l - 16f, (t + b) / 2f); nc.drawText("${vm.preset.h.toInt()}mm", l - 16f, (t + b) / 2f, tp); nc.restore()
                        nc.save(); nc.rotate(-90f, r + 18f, (t + b) / 2f); nc.drawText("${hpx}px", r + 18f, (t + b) / 2f, tp); nc.restore()
                        nc.drawText("HEAD", (l + r) / 2f, headY - 8f, bp)
                        nc.drawText("CHIN", (l + r) / 2f, chinY + 20f, bp)
                    }
                }

                // guides toggle (always available)
                Surface(onClick = { vm.showGuides = !vm.showGuides }, shape = RoundedCornerShape(16.dp),
                    color = if (vm.showGuides) guideBlue else Color(0x66000000),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Text(if (vm.showGuides) "Guides on" else "Guides off", color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
                if (scale > 1.02f) Box(Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("${(scale * 100).roundToInt()}%  -  double-tap to reset", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
    if (vm.message.isNotBlank()) Text(vm.message, fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 22.dp), maxLines = 2)
}

@Composable
private fun ExportDialog(vm: EditorViewModel) {
    val cs = MaterialTheme.colorScheme
    val bmp = vm.exportPreview ?: return
    Dialog(onDismissRequest = { vm.closeExportPreview() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = cs.surface) {
            Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (vm.exportSingle) "Single photo" else "${vm.page.label.substringBefore(" (")} - fits ${vm.fitCount().first}", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant)) {
                    ZoomableImage(bmp, Modifier.fillMaxSize().padding(8.dp))
                }
                Text("Pinch to zoom in.", fontSize = 11.sp, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallAction("Save JPG", true, !vm.busy, Modifier.weight(1f).height(48.dp)) { vm.exportJpg() }
                    SmallAction("Save PDF", true, !vm.busy, Modifier.weight(1f).height(48.dp)) { vm.exportPdf() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TonalBtn("Share", Modifier.weight(1f), !vm.busy) { vm.shareJpg() }
                    TonalBtn("Print", Modifier.weight(1f), !vm.busy) { vm.print() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TonalBtn("Share PDF", Modifier.weight(1f), !vm.busy) { vm.sharePdf() }
                    TonalBtn("Close", Modifier.weight(1f), true) { vm.closeExportPreview() }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(bmp: android.graphics.Bitmap, modifier: Modifier = Modifier) {
    var scale by remember(bmp) { mutableStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(Offset.Zero) }
    Box(modifier
        .pointerInput(bmp) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset = if (scale <= 1f) Offset.Zero else offset + pan } }
        .pointerInput(bmp) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 3f }) }, contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), "preview", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TouchUpScreen(vm: EditorViewModel) {
    val cs = MaterialTheme.colorScheme
    val bmp = vm.touchUpBmp
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E14)).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Brush touch-up", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.undoTouchUp() }) { Text("Undo", color = Color.White) }
                TextButton(onClick = { vm.resetTouchUp() }) { Text("Reset", color = Color(0xFF9AA3B2)) }
                Button(onClick = { vm.closeTouchUp() }, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) { Text("Done") }
            }
            // ALWAYS-VISIBLE brush toggle, so you can switch to pure move/zoom anytime (no accidental paint)
            Button(onClick = { vm.editDraw = !vm.editDraw }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(52.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.editDraw) Color(0xFF2F6BFF) else Color(0xFF2A2F3A), contentColor = Color.White)) {
                Text(if (vm.editDraw) "✎  BRUSH ON  -  tap for Move / Zoom" else "✋  MOVE / ZOOM  -  tap to Brush", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp).clipToBounds()) {
                if (bmp != null) {
                    val density = LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
                    val aspect = iw / ih
                    val dispW: Float; val dispH: Float
                    if (boxW / boxH > aspect) { dispH = boxH; dispW = boxH * aspect } else { dispW = boxW; dispH = boxW / aspect }
                    val left = (boxW - dispW) / 2f; val top = (boxH - dispH) / 2f
                    // zoom/pan NOT keyed on bmp, so they survive each live paint update
                    var editScale by remember { mutableStateOf(1f) }
                    var editOffset by remember { mutableStateOf(Offset.Zero) }
                    var cursor by remember { mutableStateOf<Offset?>(null) }   // IMAGE-space point being painted
                    val liftConst = with(density) { vm.brushOffset.dp.toPx() }
                    val cxp = boxW / 2f; val cyp = boxH / 2f
                    val sfit = dispW / iw
                    val bmpPaint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
                    // image<->screen is a pure uniform scale A + translate (Bx,By): no skew, so a straight
                    // screen lift stays straight (north), and toImage/fwd are EXACT inverses of the draw.
                    fun A() = sfit * editScale
                    fun bx() = (left - cxp) * editScale + cxp + editOffset.x
                    fun by0() = (top - cyp) * editScale + cyp + editOffset.y
                    fun imgMatrix(): android.graphics.Matrix = android.graphics.Matrix().apply { setScale(A(), A()); postTranslate(bx(), by0()) }
                    fun toImage(p: Offset): Offset = Offset(((p.x - bx()) / A()).coerceIn(0f, iw - 1), ((p.y - by0()) / A()).coerceIn(0f, ih - 1))
                    fun fwd(img: Offset): Offset = Offset(img.x * A() + bx(), img.y * A() + by0())
                    // paint point sits straight ABOVE the fingertip (adjustable), so your finger never covers it
                    fun lift(p: Offset): Offset = Offset(p.x, p.y - liftConst)
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = with(density) { 8.dp.toPx() }
                            var painting = false       // starts ONLY after a deliberate single-finger drag
                            var multi = false          // a 2nd finger appeared -> this gesture is zoom, never paint
                            var lastImg: Offset? = null
                            if (vm.editDraw) cursor = toImage(lift(down.position))
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    multi = true; painting = false
                                    editScale = (editScale * ev.calculateZoom()).coerceIn(1f, 8f)
                                    editOffset = if (editScale <= 1f) Offset.Zero else editOffset + ev.calculatePan()
                                    ev.changes.forEach { it.consume() }
                                } else if (pressed.size == 1 && !multi) {
                                    val ch = pressed[0]
                                    if (vm.editDraw) {
                                        if (!painting && (ch.position - down.position).getDistance() > slop) {
                                            painting = true; vm.strokeBegin()
                                            lastImg = toImage(lift(down.position)); cursor = lastImg
                                        }
                                        if (painting) {
                                            val ip = toImage(lift(ch.position)); cursor = ip
                                            // interpolate so the stroke is continuous and exactly under the ring
                                            val rImg = (vm.brushSize * minOf(iw, ih)).coerceAtLeast(2f)
                                            val prev = lastImg ?: ip
                                            val steps = ((ip - prev).getDistance() / (rImg * 0.35f)).toInt().coerceIn(1, 28)
                                            for (st in 1..steps) { val t = st / steps.toFloat(); vm.paintAt(prev.x + (ip.x - prev.x) * t, prev.y + (ip.y - prev.y) * t) }
                                            lastImg = ip; ch.consume()
                                        } else cursor = toImage(lift(ch.position))
                                    } else if (editScale > 1f) { editOffset += (ch.position - ch.previousPosition); ch.consume() }
                                }
                                if (ev.changes.none { it.pressed }) break
                            }
                        }
                    }) {
                        // draw the cut-out with the same matrix used for mapping touches
                        drawIntoCanvas { c -> c.nativeCanvas.drawBitmap(bmp, imgMatrix(), bmpPaint) }
                        if (vm.editDraw) {
                            val rScreen = vm.brushSize * minOf(iw, ih) * sfit * editScale
                            // ring is drawn at the FORWARD transform of the painted image point, so it is
                            // rendered at exactly the same place the erase/burn lands (cannot diverge)
                            val pos = cursor?.let { fwd(it) } ?: Offset(cxp, cyp)
                            drawCircle(Color.Black.copy(alpha = 0.6f), rScreen, pos, style = Stroke(width = 3.5f))
                            drawCircle(Color.White, rScreen, pos, style = Stroke(width = 1.5f))
                        }
                    }
                    if (editScale > 1.02f) Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text("${(editScale * 100).roundToInt()}%", color = Color.White, fontSize = 10.sp)
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = cs.primary) }
                }
            }
            Column(Modifier.fillMaxWidth().background(cs.surface).heightIn(max = 300.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Backdrop", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                    Box(Modifier.weight(1f)) { Segmented(listOf("Checker", "White", "Black", "Grey"), vm.editBg) { vm.editBg = it; vm.recomposeEditor() } }
                }
                if (vm.editDraw) {
                    Segmented(listOf("Erase", "Restore", "Burn", "Dodge"), vm.brushTool.ordinal) { vm.brushTool = BrushTool.entries[it] }
                    if (vm.brushTool == BrushTool.ERASE)
                        ToggleRow("Edge mode (keep hair, remove bg)", vm.brushEdge) { vm.brushEdge = it }
                    if (vm.brushTool == BrushTool.BURN || vm.brushTool == BrushTool.DODGE)
                        PSlider("Strength", vm.brushStrength * 100f, 5f..100f, whole = true, default = 50f) { vm.brushStrength = it / 100f }
                    PSlider("Brush size", vm.brushSize * 100f, 1f..20f, whole = true, default = 7f) { vm.brushSize = it / 100f }
                    PSlider("Softness", vm.brushSoft * 100f, 0f..100f, whole = true, default = 50f) { vm.brushSoft = it / 100f }
                    PSlider("Ring distance", vm.brushOffset, 20f..170f, whole = true, default = 90f) { vm.brushOffset = it }
                }
                Text(
                    if (!vm.editDraw) "Move / Zoom: one finger pans, two fingers pinch-zoom. Use Backdrop to see edges, then tap the BRUSH button to paint."
                    else "Drag one finger to paint; two fingers always zoom (never paint). " + when (vm.brushTool) {
                        BrushTool.ERASE -> "Erase removes under the circle. Edge mode keeps dark hair, removes lighter background."
                        BrushTool.RESTORE -> "Restore reverts the area to original (undoes over-erase and burn/dodge)."
                        BrushTool.BURN -> "Burn darkens only the soft hair edge to blend into a dark background."
                        BrushTool.DODGE -> "Dodge lightens only the soft hair edge to blend into a light background."
                    },
                    fontSize = 12.sp, color = cs.onSurfaceVariant
                )
            }
        }
    }
}

/* ---------- building blocks ---------- */

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, shape = RoundedCornerShape(20.dp), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable private fun Label(text: String) = Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CircleBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = CircleShape, color = cs.surface, shadowElevation = 2.dp, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = cs.onSurface) }
    }
}

@Composable
private fun PillButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, trailingArrow: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(54.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (trailingArrow) Icon(Icons.Filled.KeyboardArrowRight, null)
    }
}

@Composable
private fun TonalBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.surfaceVariant, contentColor = cs.onSurface)) { Text(text, fontSize = 13.sp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextPill(text: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = cs.surfaceVariant) {
        Text(text, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

@Composable
private fun ColumnScope.StartScreen(vm: EditorViewModel, onTake: () -> Unit, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What are you making?", fontWeight = FontWeight.Bold, fontSize = 19.sp, color = cs.onSurface)
        Text("Pick the document first - the photo is then captured and sized to the exact dimensions.", fontSize = 13.sp, color = cs.onSurfaceVariant)
        var q by remember { mutableStateOf("") }
        OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search country or document", color = cs.onSurfaceVariant) }, colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        EditorViewModel.SIZE_CATALOG.filter { q.isBlank() || it.country.contains(q, true) || it.doc.contains(q, true) }.forEach { spec ->
            val sel = spec == vm.preset
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) cs.primary.copy(alpha = 0.10f) else cs.surface)
                .border(1.dp, if (sel) cs.primary else cs.outline, RoundedCornerShape(14.dp)).clickable { vm.preset = spec }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.label, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(spec.sub, color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
                if (sel) Text("Selected", color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Surface(color = cs.surface, shadowElevation = 14.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Capturing for ${vm.preset.label}: ${vm.preset.w.toInt()} × ${vm.preset.h.toInt()} mm", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Take photo", Modifier.weight(1f)) { onTake() }
                PillButton("Pick photo", Modifier.weight(1f)) { onPick() }
            }
        }
    }
}

@Composable
private fun SmallAction(text: String, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cs.primary, contentColor = cs.onPrimary)) { Text(text, fontSize = 13.sp) }
    else FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.surfaceVariant, contentColor = cs.onSurface)) { Text(text, fontSize = 13.sp) }
}

@Composable
private fun ToggleRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = cs.onSurface, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = cs.primary, uncheckedTrackColor = cs.surfaceVariant, uncheckedBorderColor = cs.outline))
    }
}

@Composable
private fun PSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, whole: Boolean = false, default: Float = 0f, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val changed = abs(value - default) > 1e-4f
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = cs.onSurface, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(7.dp)).background(cs.surfaceVariant).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(if (whole) value.roundToInt().toString() else String.format("%.2f", value), fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).alpha(if (changed) 0.7f else 0.22f).clickable(enabled = changed) { onChange(default) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Refresh, "reset", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        Slider(value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = cs.primary, activeTrackColor = cs.primary, inactiveTrackColor = cs.surfaceVariant))
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant).padding(4.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).then(if (sel) Modifier.shadow(2.dp, RoundedCornerShape(9.dp)) else Modifier)
                .background(if (sel) cs.surface else Color.Transparent).clickable { onSelect(i) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (sel) cs.primary else cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(if (sel) cs.primary else cs.surfaceVariant)
                .clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                Text(label, color = if (sel) cs.onPrimary else cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Swatches(current: Int, palette: List<Int>, onColor: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        palette.forEach { c ->
            Box(Modifier.padding(end = 12.dp).size(34.dp).clip(CircleShape).background(Color(c))
                .border(if (c == current) 3.dp else 1.dp, if (c == current) cs.primary else cs.outline, CircleShape).clickable { onColor(c) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
