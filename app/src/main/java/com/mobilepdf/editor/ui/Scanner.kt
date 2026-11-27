package com.mobilepdf.editor.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/** What a scan produced: one cleaned-up JPEG per page, and the same pages as a ready PDF. */
class ScanResult(val pages: List<Uri>, val pdf: Uri?)

/**
 * CamScanner-style document scanner (Google ML Kit Document Scanner, on-device): live edge detection,
 * auto-capture, corner adjustment with perspective correction, filters (colour / greyscale / B&W),
 * shadow, stain and finger removal, several pages per scan, and import from the gallery.
 * It is delivered by Google Play services, so it adds almost nothing to the APK.
 *
 * Returns a function that opens the scanner. [onUnavailable] runs if this phone can't provide it
 * (no Play services); callers then fall back to the plain camera.
 */
@Composable
fun rememberDocumentScanner(
    pageLimit: Int = 100,
    onResult: (ScanResult) -> Unit,
    onUnavailable: (String) -> Unit,
    onCancel: () -> Unit = {},
): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val res = GmsDocumentScanningResult.fromActivityResultIntent(r.data)
            val pages = res?.pages?.map { it.imageUri }.orEmpty()
            if (pages.isNotEmpty()) onResult(ScanResult(pages, res?.pdf?.uri)) else onCancel()
        } else onCancel()
    }
    return {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL) // filters + ML clean-up
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()
        val activity = ctx.findActivity()
        if (activity == null) onUnavailable("No screen to show the scanner on")
        else GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> onUnavailable(e.message ?: "Document scanner unavailable") }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
