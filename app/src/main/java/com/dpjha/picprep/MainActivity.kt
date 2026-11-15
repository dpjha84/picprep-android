package com.dpjha.picprep

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dpjha.picprep.data.ImagePreset
import com.dpjha.picprep.data.ImageSaver
import com.dpjha.picprep.data.SavedImage
import com.dpjha.picprep.data.PresetStore
import com.dpjha.picprep.image.ImageCompressor
import com.dpjha.picprep.image.OutputFormat
import com.dpjha.picprep.image.ProcessedImage
import kotlinx.coroutines.launch
import java.util.Locale

private enum class ToolMode(val label: String) { COMPRESS("Compress"), RESIZE("Resize"), CONVERT("Convert"), BATCH("Batch") }
private val builtInPresets = listOf(
    ImagePreset("Job application", 200, 1200),
    ImagePreset("Exam / online form", 100, 1000),
    ImagePreset("ID / passport photo", 200, 1000),
    ImagePreset("Email attachment", 500, 1600),
    ImagePreset("Website image", 300, 2000)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { PicPrepApp() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PicPrepApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()
    val presetStore = remember { PresetStore(context) }
    val customPresets by presetStore.presets.collectAsState(initial = emptyList())
    var mode by remember { mutableStateOf(ToolMode.COMPRESS) }
    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var targetKb by remember { mutableStateOf("200") }
    var width by remember { mutableStateOf("1200") }
    var height by remember { mutableStateOf("1200") }
    var maxDimension by remember { mutableStateOf("2000") }
    var quality by remember { mutableFloatStateOf(85f) }
    var keepAspect by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf(OutputFormat.JPEG) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var outputs by remember { mutableStateOf<List<ProcessedImage>>(emptyList()) }
    var savedOutputs by remember { mutableStateOf<Map<Int, SavedImage>>(emptyMap()) }
    var previewIndex by remember { mutableStateOf<Int?>(null) }
    var presetName by remember { mutableStateOf("") }
    var showPresetDialog by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }

    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) selected = listOf(uri)
        outputs = emptyList(); status = null; error = null
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) selected = uris.take(50)
        outputs = emptyList(); status = null; error = null
    }

    fun applyPreset(p: ImagePreset) { targetKb = p.maxKb.toString(); maxDimension = p.maxDimension.toString(); width = p.maxDimension.toString(); height = p.maxDimension.toString(); showPresets = false }
    fun processImages() {
        if (selected.isEmpty() || busy) return
        val kb = targetKb.toIntOrNull()
        val maxPx = maxDimension.toIntOrNull()
        val w = width.toIntOrNull(); val h = height.toIntOrNull()
        if (mode == ToolMode.COMPRESS && (kb == null || kb < 5)) { error = "Enter a target size of at least 5 KB."; return }
        if (mode == ToolMode.RESIZE && (w == null || h == null || w !in 1..12000 || h !in 1..12000)) { error = "Enter valid dimensions between 1 and 12,000 pixels."; return }
        if (mode == ToolMode.BATCH && (kb == null || kb < 5)) { error = "Enter a target size of at least 5 KB for batch processing."; return }
        if ((mode == ToolMode.BATCH || mode == ToolMode.COMPRESS) && (maxPx == null || maxPx !in 1..12000)) { error = "Enter a maximum dimension between 1 and 12,000 pixels."; return }
        busy = true; error = null; status = null; outputs = emptyList(); savedOutputs = emptyMap(); previewIndex = null
        activity.lifecycleScope.launch {
            try {
                val engine = ImageCompressor(context.contentResolver)
                val completed = mutableListOf<ProcessedImage>()
                selected.forEachIndexed { index, uri ->
                    val result = when (mode) {
                        ToolMode.COMPRESS -> engine.process(uri, OutputFormat.JPEG, quality.toInt(), targetBytes = kb!! * 1024, maxDimension = maxDimension.toIntOrNull())
                        ToolMode.RESIZE -> engine.process(uri, format, quality.toInt(), width = w, height = h, keepAspect = keepAspect)
                        ToolMode.CONVERT -> engine.process(uri, format, quality.toInt())
                        ToolMode.BATCH -> engine.process(uri, format, quality.toInt(), targetBytes = kb!! * 1024, maxDimension = maxPx)
                    }
                    completed += result
                    status = "Processed ${index + 1} of ${selected.size} images…"
                }
                outputs = completed
                val achieved = completed.count { it.targetReached }
                status = if (mode == ToolMode.COMPRESS || mode == ToolMode.BATCH) "$achieved of ${completed.size} images met the target size." else "${completed.size} image(s) ready to save."
            } catch (t: Throwable) { error = t.message ?: "Image processing failed." }
            finally { busy = false }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6554D9), onPrimary = Color.White, secondary = Color(0xFF26335B), background = Color(0xFFF6F5FC), surface = Color.White)) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(15.dp)) { Icon(Icons.Rounded.Image, null, tint = Color.White, modifier = Modifier.padding(12.dp).size(30.dp)) }
                    Column { Text("PicPrep", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Make every image fit.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("Image tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToolMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { mode = item; outputs = emptyList(); status = null; error = null; if (item == ToolMode.BATCH) selected = emptyList() }, label = { Text(item.label) }) }
                }
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Rounded.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary); Text(if (mode == ToolMode.BATCH) "Select up to 50 images" else "Choose an image", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                        Text(when (mode) { ToolMode.COMPRESS -> "Reduce file size with a target in KB."; ToolMode.RESIZE -> "Set exact dimensions or fit within a bounding box."; ToolMode.CONVERT -> "Convert images between JPEG, PNG and WebP."; ToolMode.BATCH -> "Apply the same compression and dimension settings to multiple images." }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { if (mode == ToolMode.BATCH) multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else singlePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) { Text(if (selected.isEmpty()) "Choose ${if (mode == ToolMode.BATCH) "images" else "image"}" else "Choose ${if (mode == ToolMode.BATCH) "different images" else "another image"}") }
                        if (selected.isNotEmpty()) Text("${selected.size} image(s) selected", style = MaterialTheme.typography.labelLarge)
                        if (mode == ToolMode.COMPRESS || mode == ToolMode.BATCH) {
                            OutlinedTextField(value = targetKb, onValueChange = { targetKb = it.filter(Char::isDigit).take(6) }, label = { Text("Target maximum size (KB)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (mode == ToolMode.RESIZE) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = width, onValueChange = { width = it.filter(Char::isDigit).take(5) }, label = { Text("Width (px)") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = height, onValueChange = { height = it.filter(Char::isDigit).take(5) }, label = { Text("Height (px)") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = keepAspect, onCheckedChange = { keepAspect = it }); Text("Preserve aspect ratio") }
                        }
                        if (mode == ToolMode.BATCH) OutlinedTextField(value = maxDimension, onValueChange = { maxDimension = it.filter(Char::isDigit).take(5) }, label = { Text("Maximum longest side (px)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (mode != ToolMode.COMPRESS) {
                            Text("Output format", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutputFormat.entries.forEach { f -> FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) }) } }
                        }
                        Text("Quality: ${quality.toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Slider(value = quality, onValueChange = { quality = it }, valueRange = 10f..100f)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { showPresets = !showPresets }) { Text("Upload presets") }
                            TextButton(onClick = { presetName = ""; showPresetDialog = true }) { Text("Save custom preset") }
                        }
                        if (showPresets) {
                            Text("Built-in suggestions", style = MaterialTheme.typography.labelLarge)
                            builtInPresets.forEach { p -> TextButton(onClick = { applyPreset(p) }, modifier = Modifier.fillMaxWidth()) { Text("${p.name} · ${p.maxKb} KB · ${p.maxDimension} px") } }
                            if (customPresets.isNotEmpty()) Text("Your presets", style = MaterialTheme.typography.labelLarge)
                            customPresets.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { applyPreset(p) }, modifier = Modifier.weight(1f)) { Text("${p.name} · ${p.maxKb} KB · ${p.maxDimension} px") }; TextButton(onClick = { scope.launch { presetStore.delete(p.name) } }) { Text("Delete") } } }
                            Text("Preset values are suggestions; always check the receiving website's requirements.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { processImages() }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Processing…") } else Text("${if (mode == ToolMode.BATCH) "Process images" else "Prepare image"}")
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
                if (outputs.isNotEmpty()) {
                    Card(shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Prepared images", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            outputs.forEachIndexed { i, out ->
                                val thumbnail = remember(out.bytes) { decodePreviewBitmap(out.bytes)?.asImageBitmap() }
                                val saved = savedOutputs[i]
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Surface(modifier = Modifier.size(88.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                        if (thumbnail != null) Image(bitmap = thumbnail, contentDescription = "Prepared image preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else Icon(Icons.Rounded.Image, null, modifier = Modifier.padding(24.dp))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("Image " + (i + 1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(out.width.toString() + " × " + out.height + " · " + out.format.label, style = MaterialTheme.typography.bodySmall)
                                        Text(formatBytes(out.bytes.size.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (saved != null) Text("Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        TextButton(onClick = { previewIndex = i }, contentPadding = PaddingValues(0.dp)) { Text("Preview") }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scope.launch { try {
                                        val result = ImageSaver(context).save(out.bytes, out.format)
                                        savedOutputs = savedOutputs + (i to result)
                                        status = "Saved to Pictures/PicPrep."; error = null
                                    } catch (t: Exception) { error = t.message ?: "Could not save image." } } }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text(if (saved == null) "Save" else "Save again")
                                    }
                                    if (saved != null) {
                                        OutlinedButton(onClick = { try {
                                            val viewIntent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(saved.uri, out.format.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                            context.startActivity(Intent.createChooser(viewIntent, "Open prepared image"))
                                        } catch (t: Exception) { error = "No compatible image viewer was found." } }, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(4.dp)); Text("Open")
                                        }
                                        OutlinedButton(onClick = {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply { type = out.format.mime; putExtra(Intent.EXTRA_STREAM, saved.uri); clipData = android.content.ClipData.newUri(context.contentResolver, "Prepared image", saved.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share image"))
                                        }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(4.dp)); Text("Share") }
                                    }
                                }
                            }
                        }
                    }
                }
                Text("PicPrep · Make every image fit.", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    previewIndex?.let { index ->
        outputs.getOrNull(index)?.let { image ->
            val bitmap = remember(image.bytes) { decodePreviewBitmap(image.bytes, 1600)?.asImageBitmap() }
            androidx.compose.ui.window.Dialog(onDismissRequest = { previewIndex = null }) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (bitmap != null) Image(bitmap = bitmap, contentDescription = "Full prepared image", modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit)
                        Text(image.width.toString() + " × " + image.height + " · " + formatBytes(image.bytes.size.toLong()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scope.launch { try {
                            val saved = ImageSaver(context).save(image.bytes, image.format)
                            savedOutputs = savedOutputs + (index to saved)
                            previewIndex = null; status = "Saved to Pictures/PicPrep."; error = null
                        } catch (t: Exception) { error = t.message ?: "Could not save image." } } }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text("Save image")
                        }
                    }
                }
            }
        }
    }
    if (showPresetDialog) AlertDialog(onDismissRequest = { showPresetDialog = false }, title = { Text("Save custom preset") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(32) }, label = { Text("Preset name") }, singleLine = true)
            Text("Uses the current target KB and maximum dimension values.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        val kb = targetKb.toIntOrNull() ?: 200; val px = maxDimension.toIntOrNull() ?: 1200
        if (presetName.isNotBlank() && kb >= 5 && px in 1..12000) scope.launch { presetStore.save(ImagePreset(presetName.trim(), kb, px)); showPresetDialog = false; showPresets = true }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { showPresetDialog = false }) { Text("Cancel") } })
}

private fun formatBytes(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f)) else String.format(Locale.US, "%.1f KB", bytes / 1024f)

private fun decodePreviewBitmap(bytes: ByteArray, maxDimension: Int = 320): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}
