package com.mobilepdf.editor.ui.viewer

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.mobilepdf.editor.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Read aloud (Acrobat's Read Out Loud; PdfCraft lists it under accessibility): the phone's own
 * text-to-speech reads the document page by page from where you are, scrolling along. Offline when the
 * phone has a voice installed (most do).
 */
class ReadAloud(
    ctx: Context, private val scope: CoroutineScope, private val pageCount: Int,
    private val textOf: suspend (Int) -> String, private val onPage: (Int) -> Unit, private val onMessage: (String) -> Unit,
) {
    var page by mutableIntStateOf(-1); private set
    var speaking by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    private var ready = false
    private var pendingStart: Int? = null
    private var emptyRun = 0

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) { speaking = false; onMessage("This phone has no text-to-speech voice. Install one in Android Settings → Accessibility.") }
        else pendingStart?.let { pendingStart = null; speakPage(it) }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { scope.launch(Dispatchers.Main) { stop() } }
            override fun onDone(id: String?) {
                // the last chunk of a page: on to the next one
                if (id != null && id.endsWith(":last")) scope.launch(Dispatchers.Main) {
                    if (speaking && !paused) {
                        if (page + 1 < pageCount) speakPage(page + 1) else { stop(); onMessage("Finished reading") }
                    }
                }
            }
        })
    }

    fun start(from: Int) {
        speaking = true; paused = false; emptyRun = 0
        if (ready) speakPage(from) else pendingStart = from
    }

    fun pause() { paused = true; tts.stop() }
    fun resume() { if (page >= 0) { paused = false; speakPage(page) } }
    fun stop() { speaking = false; paused = false; page = -1; tts.stop() }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }

    private fun speakPage(p: Int) {
        page = p
        onPage(p)
        scope.launch {
            val text = runCatching { textOf(p) }.getOrDefault("").replace(Regex("-\\n(?=[a-z])"), "").replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (!speaking || paused || page != p) return@launch
            if (text.isEmpty()) {
                emptyRun++
                if (emptyRun >= 3 && p == pageCount - 1 || emptyRun >= 10) { stop(); onMessage("No text to read here. For scans, use ⋮ → Recognize text (OCR) first."); return@launch }
                if (p + 1 < pageCount) speakPage(p + 1) else stop()
                return@launch
            }
            emptyRun = 0
            // text-to-speech takes ~4000 characters at a time: cut at sentence ends
            val chunks = ArrayList<String>(); var cur = StringBuilder()
            for (sentence in text.split(Regex("(?<=[.!?])\\s+"))) {
                if (cur.length + sentence.length > 3500 && cur.isNotEmpty()) { chunks += cur.toString(); cur = StringBuilder() }
                cur.append(sentence).append(' ')
            }
            if (cur.isNotBlank()) chunks += cur.toString()
            chunks.forEachIndexed { i, c ->
                tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "p$p:$i" + if (i == chunks.lastIndex) ":last" else "")
            }
        }
    }
}

/** The bar shown while reading aloud. */
@Composable
fun ReadAloudBar(r: ReadAloud) {
    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (r.paused) "Paused · page ${r.page + 1}" else "Reading page ${r.page + 1}…",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            )
            if (r.paused) IconButton({ r.resume() }) { Icon(Icons.Filled.PlayArrow, "Resume") }
            else IconButton({ r.pause() }) { Icon(Icons.Filled.Pause, "Pause") }
            IconButton({ r.stop() }) { Icon(Icons.Filled.Stop, "Stop reading") }
        }
    }
}
