package com.mobilepdf.editor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import com.mobilepdf.editor.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobilepdf.editor.AppViewModel
import com.mobilepdf.editor.pdf.DigitalSign
import com.mobilepdf.editor.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings, storage, and About (with the open-source licences the app is built on). */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var signer by remember { mutableStateOf<DigitalSign.Signer?>(null) }
    var cacheBytes by remember { mutableStateOf(0L) }
    var fonts by remember { mutableStateOf(emptyList<File>()) }
    var licence by remember { mutableStateOf<Pair<String, String>?>(null) }
    val sigPhoto = File(ctx.filesDir, "signature.png")
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            signer = DigitalSign.current()
            cacheBytes = listOf(ctx.cacheDir).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            fonts = File(ctx.filesDir, "fonts").listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Language") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Follow the phone", "en" to "English", "ur" to "اردو").forEach { (code, label) ->
                    androidx.compose.material3.FilterChip(com.mobilepdf.editor.ui.I18n.lang == code, { vm.setLanguage(code) }, { Text(label) })
                }
            }
        }

        Section("This phone") {
            val b = vm.budget
            val kind = when (b.detectedTier) {
                com.mobilepdf.editor.pdf.Tier.HIGH -> "a fast phone: full quality, up to ${b.maxZoom.toInt()}× zoom"
                com.mobilepdf.editor.pdf.Tier.MID -> "a mid-range phone: balanced quality"
                com.mobilepdf.editor.pdf.Tier.LOW -> "a budget phone: light on memory"
            }
            Text(
                "Detected ${"%.1f".format(b.totalRamMb / 1024f)} GB memory, ${b.cores} cores: $kind",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Budget-phone mode")
                    Text("Smaller page images and caches, as on a 1–2 GB phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(vm.budget.simulateLowEnd, { vm.setLowEndMode(it) })
            }
        }

        Section("Signing") {
            val s = signer
            if (s == null) Text("No digital-signature identity. Create one from ⋮ → Digital signature… in a document.", style = MaterialTheme.typography.bodyMedium)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Digital signature: ${s.name}")
                    Text(if (s.chain.first().let { it.issuerX500Principal == it.subjectX500Principal }) "Key made on this phone" else "Imported certificate",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({ DigitalSign.forget(); refresh++ }) { Text("Remove") }
            }
            if (sigPhoto.exists()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved signature photo", Modifier.weight(1f))
                TextButton({ sigPhoto.delete(); refresh++ }) { Text("Remove") }
            }
            val drawn = vm.signatures.load()
            if (!drawn.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved hand-drawn signature", Modifier.weight(1f))
                TextButton({ vm.signatures.save(emptyList()); refresh++ }) { Text("Remove") }
            }
        }

        if (fonts.isNotEmpty()) Section("Uploaded fonts") {
            fonts.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.nameWithoutExtension, Modifier.weight(1f))
                    TextButton({ f.delete(); refresh++ }) { Text("Remove") }
                }
            }
        }

        Section("Storage") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Temporary files: ${formatSize(cacheBytes)}")
                    Text("Copies kept for undo, exports, page images. Your documents are not affected.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton({
                    scope.launch {
                        withContext(Dispatchers.IO) { vm.clearTemporaryFiles() }
                        refresh++
                        vm.message = "Temporary files cleared"
                    }
                }) { Text("Clear") }
            }
        }

        Section("About") {
            Text("PDF Editor ${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""}", style = MaterialTheme.typography.titleMedium)
            Text("Works offline. No account, no tracking: your files never leave the phone unless you share them.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Open-source software", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
            Licences.all.forEach { (name, what, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name)
                        Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ licence = name to text }) { Text("Licence") }
                }
            }
        }
    }
    licence?.let { (name, text) ->
        AlertDialog(
            onDismissRequest = { licence = null },
            title = { Text(name) },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton({ licence = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

private object Licences {
    private const val APACHE = "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in " +
        "compliance with the License. You may obtain a copy of the License at\n\nhttps://www.apache.org/licenses/LICENSE-2.0\n\n" +
        "Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an " +
        "\"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific " +
        "language governing permissions and limitations under the License."
    private const val MIT_PDFCRAFT = "MIT License\n\nCopyright (c) 2026 ArtCraft Team and the PdfCraft contributors\n\n" +
        "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation " +
        "files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, " +
        "modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software " +
        "is furnished to do so, subject to the following conditions:\n\nThe above copyright notice and this permission notice shall be " +
        "included in all copies or substantial portions of the Software.\n\nTHE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY " +
        "KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND " +
        "NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER " +
        "IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER " +
        "DEALINGS IN THE SOFTWARE.\n\n(PdfCraft is dual-licensed MIT OR Apache-2.0; this app uses it under the MIT licence.)"

    val all = listOf(
        Triple("PdfCraft", "Features and algorithms ported (redaction, hidden information, text editing, compare…)", MIT_PDFCRAFT),
        Triple("PdfBox-Android", "Reading and writing PDF files (Apache PDFBox port, by Tom Roush)", APACHE),
        Triple("Android Jetpack & Jetpack Compose", "User interface", APACHE),
        Triple("Kotlin & kotlinx.coroutines", "Programming language and libraries", APACHE),
        Triple("Google ML Kit (via Google Play services)", "On-device text recognition and document scanner",
            "Provided by Google Play services under the Google APIs Terms of Service and the ML Kit Terms of Service: " +
                "https://developers.google.com/ml-kit/terms"),
    )
}
