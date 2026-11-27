package com.mobilepdf.editor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.mobilepdf.editor.pdf.MemoryPressure
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mobilepdf.editor.data.RecentItem
import com.mobilepdf.editor.data.Recents
import com.mobilepdf.editor.data.DraftStore
import com.mobilepdf.editor.data.SignatureStore
import com.mobilepdf.editor.pdf.DeviceBudget
import com.mobilepdf.editor.pdf.NotAllowedException
import com.mobilepdf.editor.pdf.PdfOps
import com.mobilepdf.editor.util.cleanOldCache
import com.mobilepdf.editor.util.workDir
import com.mobilepdf.editor.util.cameraDir
import com.mobilepdf.editor.util.outDir
import com.mobilepdf.editor.util.copyFileTo
import com.mobilepdf.editor.util.copyToCache
import com.mobilepdf.editor.util.displayName
import com.mobilepdf.editor.util.newWorkFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { HOME, TOOLS, SETTINGS, VIEWER, ORGANIZE }

enum class ToolId { MERGE, SPLIT, EXTRACT, IMAGES, SCAN, COMPRESS, PROTECT, WATERMARK, NUMBERS, HEADER, TO_IMAGES, TEXT, IMAGES_OUT, ATTACH, HIDDEN, COMPARE, INFO }

/**
 * The document being edited. [file] is always a private, unencrypted working copy; the original is
 * only written on Save. [access] carries the original's permissions (the working copy has none left).
 */
data class DocHandle(
    val file: File, val name: String, val source: Uri?, val rev: Int = 0, val dirty: Boolean = false,
    val access: PdfOps.Access = PdfOps.Access(canModify = true, canCopy = true),
    val wasEncrypted: Boolean = false,
)

/** A finished operation the user can open, save somewhere, or share. */
data class OutFile(val files: List<File>, val title: String, val detail: String = "", val mime: String = "application/pdf")

class PendingPassword(val file: File, val name: String, val source: Uri?, val wrong: Boolean = false)

/**
 * [saved] survives the process being killed in the background — routine on 1–2 GB phones, e.g. while
 * the camera app is open for a scan. The open document and the current screen are kept there, so the
 * user comes back to exactly where they were instead of the home screen.
 */
class AppViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val recentsStore = Recents(app)
    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val signatures = SignatureStore(app)
    val drafts = DraftStore(app)

    /** Resource limits. With "low-end mode" on, a strong phone uses a 1 GB phone's limits. */
    var budget by mutableStateOf(DeviceBudget(app, settings.getBoolean("lowEnd", false)))
        private set

    init { com.mobilepdf.editor.ui.I18n.lang = settings.getString("lang", "system") ?: "system" }

    /** App language: "system", "en" or "ur". */
    fun setLanguage(lang: String) {
        com.mobilepdf.editor.ui.I18n.lang = lang
        settings.edit().putString("lang", lang).apply()
    }

    fun setLowEndMode(on: Boolean) {
        settings.edit().putBoolean("lowEnd", on).apply()
        budget = DeviceBudget(ctx, on, MemoryPressure.underPressure)
        Log.i("DeviceBudget", budget.toString())
        message = if (on) "Low-end mode on: this phone now behaves like a 1 GB phone" else "Low-end mode off"
    }

    private var screenState by mutableStateOf(saved.get<String>("screen")?.let { runCatching { Screen.valueOf(it) }.getOrNull() } ?: Screen.HOME)
    var screen: Screen
        get() = screenState
        set(v) { screenState = v; saved["screen"] = v.name }

    private var docState by mutableStateOf(restoreDoc())
    var doc: DocHandle?
        get() = docState
        set(v) { docState = v; saveDoc(v) }

    /** True when this ViewModel brought a document back after the process was killed. */
    var resumedAfterKill = docState != null
        private set
    fun consumeResumed() = resumedAfterKill.also { resumedAfterKill = false }

    private fun saveDoc(d: DocHandle?) {
        if (d == null) { saved.remove<Bundle>("doc"); return }
        saved["doc"] = Bundle().apply {
            putString("file", d.file.path); putString("name", d.name); putString("source", d.source?.toString())
            putInt("rev", d.rev); putBoolean("dirty", d.dirty); putBoolean("enc", d.wasEncrypted)
            putBoolean("mod", d.access.canModify); putBoolean("copy", d.access.canCopy)
        }
    }

    private fun restoreDoc(): DocHandle? {
        val b = saved.get<Bundle>("doc") ?: return null
        val file = File(b.getString("file") ?: return null)
        if (!file.exists()) return null
        return DocHandle(
            file, b.getString("name") ?: file.name, b.getString("source")?.let(Uri::parse), b.getInt("rev"), b.getBoolean("dirty"),
            PdfOps.Access(b.getBoolean("mod", true), b.getBoolean("copy", true)), b.getBoolean("enc"),
        )
    }
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var result by mutableStateOf<OutFile?>(null)
    var pendingPassword by mutableStateOf<PendingPassword?>(null)
    val recents = mutableStateListOf<RecentItem>()
    /** Set by shortcuts on Home; the Tools screen consumes it and opens that tool. */
    var launchTool by mutableStateOf<ToolId?>(null)

    init {
        recents.addAll(recentsStore.load())
        Log.i("DeviceBudget", budget.toString())
        viewModelScope.launch {
            snapshotFlow { MemoryPressure.underPressure }.collect { pressed ->
                if (pressed && !budget.underPressure) {
                    budget = DeviceBudget(ctx, budget.simulateLowEnd, underPressure = true)
                    Log.w("DeviceBudget", "memory pressure, stepping down: $budget")
                }
            }
        }
        if (docState == null && (screenState == Screen.VIEWER || screenState == Screen.ORGANIZE)) screenState = Screen.HOME
        viewModelScope.launch(Dispatchers.IO) {
            drafts.prune()
            ctx.cleanOldCache(keep = drafts.referencedFiles() + listOfNotNull(docState?.file))
        }
    }

    private val displayCache = HashMap<String, File>()

    /**
     * The file the OS renderer should show for [work]: a copy with annotation appearances drawn in
     * (see PdfOps.makeDisplayCopy), or [work] itself when it has none. Blocking: call off the main thread.
     */
    @Synchronized
    fun displayFile(work: File): File {
        displayCache[work.path]?.takeIf { it.exists() }?.let { return it }
        // Measured: Android 16's PdfRenderer draws annotation appearances itself (Android 7's draws
        // none), so the extra copy is skipped there. Unmeasured versions in between keep the copy.
        if (android.os.Build.VERSION.SDK_INT >= 36) return work
        val view = File(work.path.removeSuffix(".pdf") + ".view.pdf")
        val result = if (runCatching { PdfOps.makeDisplayCopy(work, view) }.getOrDefault(false)) view else work
        displayCache[work.path] = result
        return result
    }

    /** Identity of a document across sessions, used for its crash-recovery draft. */
    fun draftKey(d: DocHandle) = d.source?.toString() ?: "name:${d.name}"

    /** Restores an edited working copy from a draft (crash recovery). */
    fun adoptWorkFile(file: File) {
        doc = doc?.copy(file = file, rev = (doc?.rev ?: 0) + 1, dirty = true)
    }

    /** Runs [block] off the main thread with a busy overlay; failures become a readable message. */
    fun <T> launchOp(label: String, onDone: (T) -> Unit = {}, block: suspend () -> T) {
        viewModelScope.launch {
            busy = label
            try {
                val r = withContext(Dispatchers.IO) { block() }
                onDone(r)
            } catch (e: NotAllowedException) {
                message = e.message
            } catch (e: com.mobilepdf.editor.pdf.TextEditor.NotEditable) {
                message = e.message
            } catch (e: com.mobilepdf.editor.pdf.Ocr.Unavailable) {
                message = e.message
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                message = "A file is password-protected. Open it first, then use its unlocked copy."
            } catch (e: java.io.FileNotFoundException) {
                message = "The file is no longer available."
            } catch (e: SecurityException) {
                message = "Permission to read this file was lost. Open it again from the file picker." 
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory for this file. Try a smaller PDF or close other apps."
            } catch (e: Exception) {
                message = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    // ---------------------------------------------------------------- opening

    private sealed class Prepared {
        class Ready(val doc: DocHandle) : Prepared()
        class Locked(val p: PendingPassword) : Prepared()
    }

    /** Where to go once a document is ready: the viewer, or stay (Tools picks a working file). */
    private var showViewerAfterOpen = true

    private fun onPrepared(r: Prepared) = when (r) {
        is Prepared.Ready -> open(r.doc)
        is Prepared.Locked -> pendingPassword = r.p
    }

    fun openUri(uri: Uri, showViewer: Boolean = true) {
        showViewerAfterOpen = showViewer
        launchOp("Opening…", onDone = ::onPrepared) {
            val name = ctx.displayName(uri)
            val copy = ctx.newWorkFile()
            val encrypted = ctx.contentResolver.openInputStream(uri)!!.use { PdfOps.copyDetectingEncryption(it, copy) }
            prepare(copy, name, uri, encrypted)
        }
    }

    /** Runs on IO. Decrypts or repairs as needed so the OS renderer only ever sees a clean, unencrypted file. */
    private fun prepare(copy: File, name: String, source: Uri?, encrypted: Boolean): Prepared {
        if (encrypted) {
            val out = ctx.newWorkFile()
            val access = runCatching { PdfOps.unlock(copy, "", out) }.getOrNull()
                ?: return Prepared.Locked(PendingPassword(copy, name, source)).also { out.delete() }
            copy.delete()
            return Prepared.Ready(DocHandle(out, name, source, access = access, wasEncrypted = true))
        }
        if (!PdfOps.rendererAccepts(copy)) {
            // damaged (broken xref, truncated download…): PdfBox rebuilds it
            val fixed = ctx.newWorkFile()
            try { PdfOps.repair(copy, fixed) } catch (e: Exception) {
                fixed.delete()
                throw java.io.IOException("This file is damaged or is not a PDF.")
            }
            copy.delete()
            if (!PdfOps.rendererAccepts(fixed)) throw java.io.IOException("This file is damaged or is not a PDF.")
            message = "This file was damaged and has been repaired."
            return Prepared.Ready(DocHandle(fixed, name, source))
        }
        return Prepared.Ready(DocHandle(copy, name, source))
    }

    fun submitPassword(pw: String) {
        val p = pendingPassword ?: return
        launchOp("Unlocking…", onDone = { r: Prepared? ->
            if (r == null) pendingPassword = PendingPassword(p.file, p.name, p.source, wrong = true)
            else { pendingPassword = null; onPrepared(r) }
        }) {
            val out = ctx.newWorkFile()
            val access = PdfOps.unlock(p.file, pw, out)
            if (access == null) { out.delete(); null }
            else Prepared.Ready(DocHandle(out, p.name, p.source, access = access, wasEncrypted = true))
        }
    }

    /** Edits the original author allowed? (enforced by us, since the working copy has no encryption left) */
    fun requireEditable() {
        if (doc?.access?.canModify == false) throw NotAllowedException("The author of this PDF does not allow changes.")
    }

    fun requireCopyable() {
        if (doc?.access?.canCopy == false) throw NotAllowedException("The author of this PDF does not allow copying its content.")
    }

    private fun open(d: DocHandle) {
        resumedAfterKill = false
        doc = d
        if (showViewerAfterOpen) screen = Screen.VIEWER
        showViewerAfterOpen = true
        d.source?.let { recents.apply { clear(); addAll(recentsStore.add(RecentItem(it.toString(), d.name, System.currentTimeMillis()))) } }
    }

    fun forgetRecent(item: RecentItem) {
        recents.clear(); recents.addAll(recentsStore.remove(item.uri))
    }

    /** Opens a result (e.g. a merged PDF) as the current document. */
    fun openFile(file: File, name: String) {
        launchOp("Opening…", onDone = ::onPrepared) {
            val copy = ctx.newWorkFile()
            val encrypted = file.inputStream().use { PdfOps.copyDetectingEncryption(it, copy) }
            prepare(copy, name, null, encrypted) // e.g. the result of "Protect" is encrypted
        }
    }

    // ---------------------------------------------------------------- editing / saving

    /** Replace the working copy after an edit (organize, annotate…). The viewer reloads from [rev]. */
    fun replaceDoc(file: File) {
        doc = doc?.copy(file = file, rev = (doc?.rev ?: 0) + 1, dirty = true)
    }

    /** [discard] = the user chose to throw away unsaved work, so its recovery draft goes too. */
    /** Settings → Clear: every scratch file except the open document's working copy and recovery drafts' files. */
    fun clearTemporaryFiles() {
        val keep = drafts.referencedFiles() + listOfNotNull(doc?.file)
        listOf(ctx.workDir(), ctx.outDir(), ctx.cameraDir()).forEach { d ->
            d.walkTopDown().filter { it.isFile && it !in keep }.forEach { it.delete() }
        }
        ctx.cacheDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    fun closeDoc(discard: Boolean = false) {
        if (discard) doc?.let { drafts.delete(draftKey(it)) }
        // the working copies (kept for undo) go as soon as the file is closed: after a redaction they
        // still hold what was removed. Only a recovery draft's own file stays. Listed now, deleted in the
        // background, so a file opened next is never caught by it.
        val stale = ctx.workDir().listFiles()?.toList().orEmpty()
        val keep = drafts.referencedFiles()
        viewModelScope.launch(Dispatchers.IO) { stale.filter { it !in keep }.forEach { it.delete() } }
        doc = null
        screen = Screen.HOME
    }

    /** Writes the working copy back over the file it came from. Returns false if the source is not writable. */
    fun saveToSource(): Boolean {
        val d = doc ?: return false
        val src = d.source ?: return false
        return try {
            ctx.copyFileTo(d.file, src)
            doc = d.copy(dirty = false)
            message = "Saved"
            true
        } catch (e: Exception) {
            false
        }
    }

    /** "Save as": writes the working copy to a new place and makes that the document's home. */
    fun saveAs(uri: Uri) {
        val d = doc ?: return
        launchOp<Unit>("Saving…", onDone = {
            doc = doc?.copy(source = uri, name = ctx.displayName(uri), dirty = false)
            recents.apply { clear(); addAll(recentsStore.add(RecentItem(uri.toString(), ctx.displayName(uri), System.currentTimeMillis()))) }
            message = "Saved"
        }) { ctx.copyFileTo(d.file, uri) }
    }

    fun saveCopyTo(uri: Uri, file: File) {
        launchOp<Unit>("Saving…", onDone = { message = "Saved" }) { ctx.copyFileTo(file, uri) }
    }

    /** Saves several files (split parts, page images) into a folder the user picked. */
    fun saveManyTo(tree: Uri, files: List<File>, mime: String) {
        launchOp<Unit>("Saving…", onDone = { message = "Saved ${files.size} files" }) {
            val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: error("Folder not available")
            for (f in files) {
                val target = dir.createFile(mime, f.name.substringBeforeLast('.')) ?: error("Cannot create ${f.name}")
                ctx.copyFileTo(f, target.uri)
            }
        }
    }

    fun showResult(r: OutFile) { result = r }
}
