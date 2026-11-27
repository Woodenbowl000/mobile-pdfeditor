package com.mobilepdf.editor.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.MarkKind
import com.mobilepdf.editor.annot.ShapeKind
import com.mobilepdf.editor.pdf.RedactPattern

/** Redact tool options: what a drag marks, find & mark, and apply. */
@Composable
fun RedactBar(state: ViewerState, onFind: () -> Unit, onApply: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!state.redactArea, { state.redactArea = false }, { Text("Words") })
            FilterChip(state.redactArea, { state.redactArea = true }, { Text("Area") })
            TextButton(onFind) { Text("Find…", maxLines = 1) }
            Spacer1()
            val n = state.redactCount
            Button(
                onApply, enabled = n > 0,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(if (n > 0) "Apply $n" else "Apply", maxLines = 1, softWrap = false) }
        }
        Text(
            if (state.redactArea) "Drag a box over anything to remove" else "Drag over the words to remove · tap Move to adjust a mark",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable private fun Spacer1() = androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))

/** Actions for text selected in Read mode. */
@Composable
fun SelectionActions(
    sel: TextSel, canCopy: Boolean, canModify: Boolean,
    onCopy: () -> Unit, onShare: () -> Unit, onHighlight: () -> Unit, onRedact: () -> Unit, onAll: () -> Unit, onClose: () -> Unit,
) {
    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                sel.text.replace('\n', ' ').ifBlank { "(no text)" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onCopy, enabled = canCopy) { Icon(Icons.Filled.ContentCopy, null); Text(" Copy") }
                TextButton(onShare, enabled = canCopy) { Icon(Icons.Filled.Share, null); Text(" Share") }
                TextButton(onAll) { Icon(Icons.Filled.SelectAll, null); Text(" All") }
                if (canModify) {
                    TextButton(onHighlight) { Icon(Icons.Filled.Highlight, null); Text(" Highlight") }
                    TextButton(onRedact) { Icon(Icons.Filled.VisibilityOff, null); Text(" Redact") }
                }
                IconButton(onClose) { Icon(Icons.Filled.Close, "Clear selection") }
            }
        }
    }
}

/**
 * Find & redact (PdfCraft "Find text to redact"): words or phrases, and built-in patterns. Every match
 * across the document gets a mark, which can be reviewed before applying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactFindSheet(onDismiss: () -> Unit, onMark: (List<String>, Boolean, Set<RedactPattern>) -> Unit) {
    var words by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var patterns by remember { mutableStateOf(emptySet<RedactPattern>()) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding().navigationBarsPadding()) {
            Text("Find & mark for redaction", style = MaterialTheme.typography.titleLarge)
            Text(
                "Every match in the document gets a mark. Check them, then tap Apply.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                words, { words = it }, label = { Text("Words or phrases (comma between them)") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(matchCase, { matchCase = it }); Text("  Match case")
            }
            Text("Also find", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RedactPattern.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { patterns = if (p in patterns) patterns - p else patterns + p }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p in patterns, { patterns = if (it) patterns + p else patterns - p })
                    Text(p.label)
                }
            }
            val terms = words.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            Button(
                { onMark(terms, matchCase, patterns) }, enabled = terms.isNotEmpty() || patterns.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Mark all matches") }
        }
    }
}

/** What an annotation is, in words, and its text (Comments list). */
fun describe(a: Annot): Pair<String, String> = when (a) {
    is Annot.Note -> "Note" to a.text
    is Annot.Text -> "Text" to a.text
    is Annot.Mark -> when (a.kind) { MarkKind.HIGHLIGHT -> "Highlight"; MarkKind.UNDERLINE -> "Underline"; MarkKind.STRIKE -> "Strikethrough" } to ""
    is Annot.Ink -> (if (a.marker) "Marker" else "Drawing") to ""
    is Annot.Shape -> when (a.kind) { ShapeKind.RECT -> "Box"; ShapeKind.OVAL -> "Oval"; ShapeKind.LINE -> "Line"; ShapeKind.ARROW -> "Arrow"; ShapeKind.CLOUD -> "Cloud"; ShapeKind.AREA -> "Area highlight"
        ShapeKind.TICK -> "Tick"; ShapeKind.CROSS -> "Cross"; ShapeKind.DOT -> "Dot" } to ""
    is Annot.Stamp -> (if (a.image == null) "Signature" else if ("/images/" in a.image) "Picture" else "Signature (photo)") to ""
    is Annot.Replace -> "Edited text" to a.text
    is Annot.Redact -> "Redaction mark (not applied)" to a.label
    is Annot.Seal -> "Stamp" to a.text
}

/** Every change on every page (PdfCraft's Comments panel): jump to it, edit a note, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(state: ViewerState, onGoTo: (Int) -> Unit, onEdit: (Int, Annot) -> Unit, onDismiss: () -> Unit) {
    val items = state.annots.toSortedMap().flatMap { (p, l) -> l.map { p to it } }
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(bottom = 12.dp).navigationBarsPadding()) {
            Text(
                "Comments & changes (${items.size})", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (items.isEmpty()) Text(
                "Nothing yet. Notes, highlights, drawings and other changes you make are listed here.",
                Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(items, key = { it.second.id }) { (page, a) ->
                    val (kind, text) = describe(a)
                    Row(
                        Modifier.fillMaxWidth().clickable { if (a is Annot.Note) onEdit(page, a) else onGoTo(page) }
                            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("$kind · page ${page + 1}", style = MaterialTheme.typography.titleSmall)
                            if (text.isNotBlank()) Text(text.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton({ state.remove(page, a.id) }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
            if (items.isNotEmpty()) OutlinedButton({ onDismiss() }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text("Close") }
        }
    }
}


/** Stamp presets (PdfCraft's standard business stamps) plus a custom one. */
@Composable
fun StampDialog(onDismiss: () -> Unit, onPick: (String, Int) -> Unit) {
    val today = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
    val green = 0xFF2E7D32.toInt(); val red = 0xFFC62828.toInt(); val blue = 0xFF1565C0.toInt(); val grey = 0xFF616161.toInt()
    val presets = listOf(
        "APPROVED" to green, "PAID" to green, "COMPLETED" to green,
        "REJECTED" to red, "CONFIDENTIAL" to red, "URGENT" to red,
        "RECEIVED $today" to blue, "FINAL" to blue, "FOR REVIEW" to blue,
        "DRAFT" to grey, "COPY" to grey, "VOID" to grey,
    )
    var custom by remember { mutableStateOf("") }
    var customColor by remember { mutableStateOf(blue) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stamp") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // short labels two to a row; long ones (CONFIDENTIAL, a dated stamp) get a whole row
                val rows = ArrayList<List<Pair<String, Int>>>()
                var pending: Pair<String, Int>? = null
                for (p in presets) {
                    if (p.first.length > 10) { pending?.let { rows += listOf(it) }; pending = null; rows += listOf(p) }
                    else if (pending == null) pending = p else { rows += listOf(pending!!, p); pending = null }
                }
                pending?.let { rows += listOf(it) }
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        row.forEach { (t, c) ->
                            OutlinedButton({ onPick(t, c) }, Modifier.weight(1f), border = androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color(c))) {
                                Text(t, color = androidx.compose.ui.graphics.Color(c), maxLines = 1, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                    }
                }
                OutlinedTextField(custom, { custom = it.take(40) }, label = { Text("Your own text") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(blue, green, red, grey).forEach { c ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(if (customColor == c) 30.dp else 24.dp)
                                .background(androidx.compose.ui.graphics.Color(c), androidx.compose.foundation.shape.CircleShape)
                                .clickable { customColor = c },
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton({ onPick(custom.trim().uppercase(), customColor) }, enabled = custom.isNotBlank()) { Text("Add") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
