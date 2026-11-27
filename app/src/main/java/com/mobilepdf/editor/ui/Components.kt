@file:OptIn(ExperimentalMaterial3Api::class)

package com.mobilepdf.editor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.AppViewModel
import com.mobilepdf.editor.util.formatSize
import com.mobilepdf.editor.util.shareFiles

/** Full-screen dimmed spinner shown while a background operation runs. */
@Composable
fun BusyOverlay(label: String?) {
    if (label == null) return
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectDragGestures { _, _ -> } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun PasswordDialog(name: String, wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, isError = wrong,
                    label = { Text("Password") },
                    supportingText = { if (wrong) Text("Wrong password, try again") },
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") } },
                )
            }
        },
        confirmButton = { TextButton({ onSubmit(pw) }, enabled = pw.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

/** Shown after any operation produces a file: open it, save it somewhere, or share it. */
@Composable
fun ResultSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val r = vm.result ?: return
    val ctx = LocalContext.current
    val single = r.files.size == 1
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(r.mime)) { uri: Uri? ->
        if (uri != null && single) vm.saveCopyTo(uri, r.files[0])
    }
    val treeSaver = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) vm.saveManyTo(tree, r.files, r.mime)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(r.title, style = MaterialTheme.typography.titleLarge)
            }
            val size = formatSize(r.files.sumOf { it.length() })
            Text(
                listOf(if (single) r.files[0].name else "${r.files.size} files", size, r.detail).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            if (single && r.mime == "application/pdf") {
                Button({ vm.openFile(r.files[0], r.files[0].name); onDismiss() }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Visibility, null); Spacer(Modifier.size(8.dp)); Text("Open")
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    if (single) saver.launch(r.files[0].name) else treeSaver.launch(null)
                }, Modifier.weight(1f)) { Icon(Icons.Filled.Save, null); Spacer(Modifier.size(8.dp)); Text("Save to…") }
                OutlinedButton({ ctx.shareFiles(r.files, r.mime) }, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.size(8.dp)); Text("Share")
                }
            }
        }
    }
}

/** Finger signature pad. Returns strokes normalized to the unit square. */
@Composable
fun SignaturePad(
    initial: List<List<Pair<Float, Float>>>?,
    onDone: (List<List<Pair<Float, Float>>>) -> Unit,
    onCancel: () -> Unit,
    onPhoto: () -> Unit = {},
    savedPhoto: Boolean = false,
    onSavedPhoto: () -> Unit = {},
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Draw your signature") },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                        .pointerInput(Unit) {
                            // Raw touch tracking, not a drag detector: a drag detector waits for the finger to move
                            // a few mm first and ignores taps, so i-dots, short dashes and the first mm of every
                            // stroke were lost. Here each touch is a stroke from the exact point it lands; any
                            // number of strokes can be made, lifting the finger in between.
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                var pts = listOf(down.position)
                                current = pts
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    if (ch.positionChanged()) { pts = pts + ch.position; current = pts; ch.consume() }
                                }
                                strokes += pts // a single point is a dot
                                current = emptyList()
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (s in strokes + listOf(current)) {
                            if (s.isEmpty()) continue
                            if (s.size == 1) { drawCircle(Color(0xFF1A237E), 4f, s[0]); continue }
                            val p = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(p, Color(0xFF1A237E), style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text("Sign here with your finger", Modifier.align(Alignment.Center), color = Color.Gray)
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton({ strokes.clear() }) { Text("Clear") }
                    TextButton({ if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text("Undo stroke") }
                    if (initial != null) TextButton({ onDone(initial) }) { Text("Use saved") }
                }
                // a signature written on paper: photograph it (or pick the photo) instead of drawing
                OutlinedButton(onPhoto, Modifier.fillMaxWidth()) { Text("Use a photo of my signature") }
                if (savedPhoto) TextButton(onSavedPhoto, Modifier.fillMaxWidth()) { Text("Use my saved signature photo") }
                Row {
                }
            }
        },
        confirmButton = {
            TextButton({
                // normalize to the strokes' own bounding box so the stamp has a tight, predictable size
                val all = strokes.flatten()
                val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
                val minY = all.minOf { it.y }
                val sw = (maxX - minX).coerceAtLeast(1f)
                // x in 0..1, y in 0..(height/width): the aspect ratio is preserved in the data itself
                onDone(strokes.map { s -> s.map { (it.x - minX) / sw to (it.y - minY) / sw } })
            }, enabled = strokes.isNotEmpty()) { Text("Done") }
        },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

/** Simple confirm/ask dialog for plain text input. */
@Composable
fun TextPrompt(
    title: String, initial: String = "", label: String = "", multiline: Boolean = false,
    confirm: String = "OK", allowEmpty: Boolean = false, onDone: (String) -> Unit, onCancel: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    // cursor at the end and the keyboard up straight away
    var t by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    t, { t = it }, label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 3 else 1,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                extra?.invoke()
            }
        },
        confirmButton = { TextButton({ onDone(t.text) }, enabled = allowEmpty || t.text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } },
    )
}

/**
 * Shown only in low-end mode: live Java heap and total (Java + native) allocations against what a
 * 1 GB phone tolerates. Bitmaps live in the Java heap on Android 7 and in native memory on 8+, so
 * both are tracked. New peaks are logged under tag "LowEnd" so tests can read them over adb.
 */
@Composable
fun LowEndMeter(budget: com.mobilepdf.editor.pdf.DeviceBudget, modifier: Modifier = Modifier) {
    var java by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }
    var peakJava by remember { mutableStateOf(0L) }
    var peakTotal by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val rt = Runtime.getRuntime()
        while (true) {
            java = rt.totalMemory() - rt.freeMemory()
            total = java + android.os.Debug.getNativeHeapAllocatedSize()
            if (java > peakJava || total > peakTotal) {
                peakJava = maxOf(peakJava, java); peakTotal = maxOf(peakTotal, total)
                android.util.Log.i("LowEnd", "peak java=${peakJava shr 20}MB total=${peakTotal shr 20}MB")
            }
            if (java > budget.lowEndJavaHeapLimit || total > budget.lowEndTotalLimit) {
                android.util.Log.w("LowEnd", "OVER 1GB-PHONE BUDGET java=${java shr 20}MB total=${total shr 20}MB")
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val over = java > budget.lowEndJavaHeapLimit || total > budget.lowEndTotalLimit
    Surface(
        modifier.statusBarsPadding().padding(top = 4.dp, end = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (over) Color(0xE6C62828) else Color(0xCC1B5E20),
    ) {
        Text(
            "1 GB mode · heap ${java shr 20}/${budget.lowEndJavaHeapLimit shr 20} · all ${total shr 20}/${budget.lowEndTotalLimit shr 20} MB" +
                " · peak ${peakTotal shr 20}",
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall,
        )
    }
}

