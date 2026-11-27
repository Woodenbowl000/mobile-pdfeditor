@file:OptIn(ExperimentalMaterial3Api::class)

package com.mobilepdf.editor.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.pdf.Bookmark
import com.mobilepdf.editor.pdf.FieldType
import com.mobilepdf.editor.pdf.FormField
import com.mobilepdf.editor.pdf.Forms

/**
 * Every form field as a native Android input. On a small screen this is far easier than tapping
 * tiny boxes on the page (both work).
 */
@Composable
fun FormSheet(state: ViewerState, onDismiss: () -> Unit, onGoTo: (Int) -> Unit, onSign: (FormField) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fill form", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({ state.formValues.clear() }, enabled = state.formDirty) { Text("Reset") }
            }
            Text(
                "${state.fields.size} fields" + if (state.formDirty) " · ${state.formValues.size} changed" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Highlight fields on the page", state.showFields) { state.showFields = it }
            SwitchRow("Flatten when saving (no longer editable)", state.flattenOnSave) { state.flattenOnSave = it }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.fields, key = { it.name }) { f -> FieldInput(state, f, onGoTo, onSign) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

@Composable
private fun FieldInput(state: ViewerState, f: FormField, onGoTo: (Int) -> Unit, onSign: (FormField) -> Unit) {
    val value = state.valueOf(f)
    val page = f.widgets.first().page
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton({ onGoTo(page) }) { Text("Page ${page + 1}") }
        }
        when (f.type) {
            FieldType.TEXT -> OutlinedTextField(
                value, { state.setValue(f, it, format = false) },
                Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && f.format != null) state.setValue(f, state.valueOf(f)) },
                enabled = !f.readOnly && f.calc == null,
                singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1,
                supportingText = if (f.calc != null) ({ Text("Calculated from other fields") }) else null,
                keyboardOptions = if (f.format != null) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                    else androidx.compose.foundation.text.KeyboardOptions.Default,
            )
            FieldType.CHECK -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(value == Forms.CHECKED, { state.setValue(f, if (it) Forms.CHECKED else Forms.UNCHECKED) }, enabled = !f.readOnly)
                Text(if (value == Forms.CHECKED) "Checked" else "Not checked")
            }
            FieldType.RADIO -> Column {
                f.widgets.mapNotNull { it.onValue }.distinct().forEach { opt ->
                    Row(Modifier.clickable(enabled = !f.readOnly) { state.setValue(f, opt) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(value == opt, { state.setValue(f, opt) }, enabled = !f.readOnly)
                        Text(opt)
                    }
                }
            }
            FieldType.CHOICE -> ChoiceInput(f.options, value, !f.readOnly) { state.setValue(f, it) }
            FieldType.SIGNATURE -> OutlinedButton({ onSign(f) }, enabled = !f.readOnly) { Text("Sign here") }
        }
    }
}

@Composable
private fun ChoiceInput(options: List<String>, value: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text(value.ifEmpty { "Choose…" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(o) }, { onPick(o); open = false }) }
        }
    }
}

/** Edit one text or choice field after tapping it on the page. */
@Composable
fun FieldEditDialog(state: ViewerState, f: FormField, onDismiss: () -> Unit) {
    var text by remember(f.name) { mutableStateOf(state.valueOf(f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            if (f.type == FieldType.CHOICE) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    f.options.forEach { o ->
                        Row(Modifier.fillMaxWidth().clickable { state.setValue(f, o); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(state.valueOf(f) == o, { state.setValue(f, o); onDismiss() })
                            Text(o)
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth(), singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1,
                    keyboardOptions = if (f.format != null) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
                        else androidx.compose.foundation.text.KeyboardOptions.Default,
                    supportingText = if (f.calc != null) ({ Text("Calculated: changes when the fields it adds up change") }) else null,
                )
            }
        },
        confirmButton = {
            if (f.type != FieldType.CHOICE) TextButton({ state.setValue(f, text); onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** The document outline, with a filter box: matches keep their position so the structure stays readable. */
@Composable
fun BookmarksSheet(
    items: List<Bookmark>?, onDismiss: () -> Unit, onGoTo: (Int) -> Unit,
    canEdit: Boolean = false, currentPage: Int = 0, suggestTitle: suspend (Int) -> String = { "Page ${it + 1}" },
    onSave: (List<Bookmark>) -> Unit = {},
) {
    var q by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<androidx.compose.runtime.snapshots.SnapshotStateList<Bookmark>?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bookmarks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (canEdit && items != null) {
                    val e = editing
                    if (e == null) TextButton({ editing = androidx.compose.runtime.mutableStateListOf<Bookmark>().apply { addAll(items) } }) { Text("Edit") }
                    else TextButton({ editing = null; if (e.toList() != items) onSave(e.toList()) }) { Text("Done") }
                }
            }
            val e = editing
            if (e != null) { BookmarkEditor(e, currentPage, suggestTitle); return@Column }
            when {
                items == null -> Text("Loading…", Modifier.padding(vertical = 24.dp))
                items.isEmpty() -> Text(
                    "This document has no bookmarks.", Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    if (items.size > 12) OutlinedTextField(
                        q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        singleLine = true, placeholder = { Text("Search bookmarks") },
                    )
                    val shown = if (q.isBlank()) items else items.filter { it.title.contains(q.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shown.size) { i ->
                            val b = shown[i]
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = b.page >= 0) { onGoTo(b.page) }
                                    .padding(start = (if (q.isBlank()) b.depth * 16 else 0).dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    b.title.ifBlank { "(untitled)" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = if (b.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                )
                                if (b.page >= 0) Text("${b.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Edit mode of the bookmarks sheet: add for the current page, rename, delete, move, nest. */
@Composable
private fun BookmarkEditor(
    list: androidx.compose.runtime.snapshots.SnapshotStateList<Bookmark>, currentPage: Int, suggestTitle: suspend (Int) -> String,
) {
    var rename by remember { mutableStateOf<Int?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    OutlinedButton({
        scope.launch {
            val title = suggestTitle(currentPage)
            // after the last bookmark that points at an earlier (or this) page, at its level
            val at = list.indexOfLast { it.page in 0..currentPage } + 1
            val depth = list.getOrNull(at - 1)?.depth ?: 0
            list.add(at, Bookmark(title, depth, currentPage))
            rename = at
        }
    }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Add bookmark for page ${currentPage + 1}") }
    if (list.isEmpty()) Text("No bookmarks. Saving removes the outline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list.size) { i ->
            val b = list[i]
            Row(Modifier.fillMaxWidth().padding(start = (b.depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { rename = i }.padding(vertical = 6.dp)) {
                    Text(b.title.ifBlank { "(untitled)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (b.page >= 0) "Page ${b.page + 1}" else "No page", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val tiny = Modifier.size(36.dp)
                androidx.compose.material3.IconButton({ if (i > 0) { val x = list.removeAt(i); list.add(i - 1, x) } }, tiny, enabled = i > 0) {
                    Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowUp, "Move up")
                }
                androidx.compose.material3.IconButton({ if (i < list.lastIndex) { val x = list.removeAt(i); list.add(i + 1, x) } }, tiny, enabled = i < list.lastIndex) {
                    Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, "Move down")
                }
                androidx.compose.material3.IconButton({ list[i] = b.copy(depth = (b.depth - 1).coerceAtLeast(0)) }, tiny, enabled = b.depth > 0) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Less indent")
                }
                val canIndent = i > 0 && b.depth <= list[i - 1].depth
                androidx.compose.material3.IconButton({ list[i] = b.copy(depth = b.depth + 1) }, tiny, enabled = canIndent) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, "Indent")
                }
                androidx.compose.material3.IconButton({ list.removeAt(i) }, tiny) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    rename?.let { i ->
        val b = list.getOrNull(i)
        if (b == null) rename = null
        else com.mobilepdf.editor.ui.TextPrompt(
            "Bookmark", initial = b.title, label = "Title", confirm = "OK",
            onDone = { t -> list[i] = b.copy(title = t.trim()); rename = null }, onCancel = { rename = null },
        )
    }
}
