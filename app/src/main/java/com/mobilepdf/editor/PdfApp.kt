package com.mobilepdf.editor

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext) // just stores the context; fonts/resources load lazily
    }

    /**
     * Budget phones send these constantly. Dropping the page cache (it is rebuilt on demand) is what
     * keeps the low-memory killer from choosing us, especially while we are in the background.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) com.mobilepdf.editor.pdf.PageRenderer.trimAll()
        // RUNNING_LOW / RUNNING_CRITICAL arrive while we are in front: the phone is struggling right now,
        // so the app steps down a tier for the rest of the session (smaller cache and bitmaps).
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            com.mobilepdf.editor.pdf.MemoryPressure.underPressure = true
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.mobilepdf.editor.pdf.PageRenderer.trimAll()
        com.mobilepdf.editor.pdf.MemoryPressure.underPressure = true
    }
}
