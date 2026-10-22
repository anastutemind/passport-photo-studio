package com.aps.passportphoto

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    EditorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.loadUri(uri) }

    fun pick() = picker.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    )

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passport Photo Studio", fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = { pick() }) { Text("Pick photo") } }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- preview ----
            Card(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp)
                        .background(Color(0xFFEDEFF3)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = vm.preview
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "preview",
                            modifier = Modifier
                                .padding(16.dp)
                                .heightIn(max = 420.dp)
                        )
                    } else {
                        OutlinedButton(onClick = { pick() }) { Text("Choose a photo to begin") }
                    }
                }
            }

            Text(vm.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)

            if (vm.hasImage) {
                // ---- position ----
                SectionCard("Position & alignment") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.autoAlign() }, enabled = !vm.busy) { Text("Auto align") }
                        OutlinedButton(onClick = { vm.removeBackground() }, enabled = !vm.busy) { Text("Remove bg") }
                        OutlinedButton(onClick = { vm.reset() }) { Text("Reset") }
                    }
                    LabeledSlider("Zoom", vm.scale, 0.4f..3f) { vm.scale = it; vm.onParamChanged() }
                    LabeledSlider("Horizontal", vm.offX, -0.5f..0.5f) { vm.offX = it; vm.onParamChanged() }
                    LabeledSlider("Vertical", vm.offY, -0.5f..0.5f) { vm.offY = it; vm.onParamChanged() }
                    LabeledSlider("Rotation", vm.rot, -20f..20f) { vm.rot = it; vm.onParamChanged() }
                }

                // ---- adjustments ----
                SectionCard("Adjustments") {
                    LabeledSlider("Brightness", vm.brightness, 0.5f..1.5f) { vm.brightness = it; vm.onParamChanged() }
                    LabeledSlider("Contrast", vm.contrast, 0.5f..1.5f) { vm.contrast = it; vm.onParamChanged() }
                    LabeledSlider("Saturation", vm.saturation, 0f..2f) { vm.saturation = it; vm.onParamChanged() }
                    LabeledSlider("Skin blur", vm.skinBlur, 0f..100f, true) { vm.skinBlur = it; vm.onParamChanged() }
                }

                // ---- background ----
                SectionCard("Background") {
                    Text(
                        if (vm.bgRemoved) "Pick a background color:"
                        else "Tap \"Remove bg\" first, then pick a color.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(6.dp))
                    ColorChooser(
                        current = vm.bgColor,
                        palette = listOf(
                            AColor.WHITE, AColor.rgb(43, 95, 176), AColor.rgb(17, 17, 17),
                            AColor.rgb(214, 227, 245), AColor.rgb(230, 230, 230), AColor.rgb(245, 240, 225)
                        )
                    ) { vm.bgColor = it; vm.onParamChanged() }
                }

                // ---- size ----
                SectionCard("Photo size") {
                    DropdownPicker(
                        options = EditorViewModel.SIZE_PRESETS.map { it.label },
                        selected = vm.preset.label
                    ) { idx -> vm.preset = EditorViewModel.SIZE_PRESETS[idx]; vm.onParamChanged() }
                }

                // ---- strip ----
                SectionCard("Bottom strip") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.stripOn, onCheckedChange = { vm.stripOn = it; vm.onParamChanged() })
                        Text("Show strip")
                    }
                    if (vm.stripOn) {
                        LabeledSlider("Height (mm)", vm.stripH, 3f..30f, true) { vm.stripH = it; vm.onParamChanged() }
                        Text("Strip background", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        ColorChooser(
                            current = vm.stripBg,
                            palette = listOf(AColor.WHITE, AColor.rgb(17, 17, 17), AColor.rgb(43, 95, 176), AColor.rgb(230, 230, 230))
                        ) { vm.stripBg = it; vm.onParamChanged() }
                        Spacer(Modifier.height(4.dp))
                        Text("Text color", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                        ColorChooser(
                            current = vm.stripFg,
                            palette = listOf(AColor.BLACK, AColor.WHITE, AColor.rgb(43, 95, 176), AColor.rgb(176, 0, 0))
                        ) { vm.stripFg = it; vm.onParamChanged() }
                        Spacer(Modifier.height(8.dp))
                        val hints = listOf("Candidate name", "Date of birth", "Date", "Extra line", "Extra line", "Extra line")
                        vm.stripLines.forEachIndexed { i, line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = line.on, onCheckedChange = { line.on = it; vm.onParamChanged() })
                                OutlinedTextField(
                                    value = line.text,
                                    onValueChange = { line.text = it; vm.onParamChanged() },
                                    placeholder = { Text(hints.getOrElse(i) { "Line ${i + 1}" }) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        TextButton(onClick = { vm.stripLines.add(StripLine(true, "")); vm.onParamChanged() }) {
                            Text("+ Add line")
                        }
                    }
                }

                // ---- print & export ----
                SectionCard("Print & export") {
                    DropdownPicker(
                        options = EditorViewModel.PAGE_PRESETS.map { it.label },
                        selected = vm.page.label
                    ) { idx -> vm.page = EditorViewModel.PAGE_PRESETS[idx]; vm.onParamChanged() }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledSlider("Margin (mm)", vm.margin, 0f..30f, true, Modifier.weight(1f)) { vm.margin = it; vm.onParamChanged() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledSlider("Gap (mm)", vm.gap, 0f..20f, true, Modifier.weight(1f)) { vm.gap = it; vm.onParamChanged() }
                    }
                    val (count, cols, rows) = vm.fitCount()
                    Text("Fits $count photos per page ($cols x $rows).", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.exportJpg() }, enabled = !vm.busy) { Text("Save JPG") }
                        Button(onClick = { vm.exportPdf() }, enabled = !vm.busy) { Text("Save PDF") }
                        OutlinedButton(onClick = { vm.print() }, enabled = !vm.busy) { Text("Print") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (vm.busy) {
        Box(
            Modifier.fillMaxSize().background(Color(0x88000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(vm.busyMsg, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
    }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    whole: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    Column(modifier) {
        Row {
            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                if (whole) value.roundToInt().toString() else String.format("%.2f", value),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun ColorChooser(current: Int, palette: List<Int>, onColor: (Int) -> Unit) {
    var showRgb by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        palette.forEach { c ->
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (c == current) 3.dp else 1.dp,
                        color = if (c == current) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB),
                        shape = CircleShape
                    )
                    .clickable { onColor(c) }
            )
        }
        TextButton(onClick = { showRgb = !showRgb }) { Text(if (showRgb) "Hide" else "Custom") }
    }
    if (showRgb) {
        val r = AColor.red(current).toFloat()
        val g = AColor.green(current).toFloat()
        val b = AColor.blue(current).toFloat()
        LabeledSlider("R", r, 0f..255f, true) { onColor(AColor.rgb(it.roundToInt(), g.roundToInt(), b.roundToInt())) }
        LabeledSlider("G", g, 0f..255f, true) { onColor(AColor.rgb(r.roundToInt(), it.roundToInt(), b.roundToInt())) }
        LabeledSlider("B", b, 0f..255f, true) { onColor(AColor.rgb(r.roundToInt(), g.roundToInt(), it.roundToInt())) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownPicker(options: List<String>, selected: String, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(i); expanded = false })
            }
        }
    }
}
