package com.mobilepdf.editor.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/**
 * App language. "system" follows the phone; "en" and "ur" force English or Urdu. Urdu also turns the
 * interface right-to-left (the PDF pages themselves keep their own direction).
 *
 * The English text in the code is the key: [tr] looks it up, so every screen is translated by importing
 * [Text] from here instead of from Material 3. Messages with numbers or names use [PATTERNS].
 */
object I18n {
    var lang by mutableStateOf("system")
    val urdu: Boolean get() = lang == "ur" || (lang == "system" && Locale.getDefault().language == "ur")

    fun tr(s: String): String {
        if (!urdu) return s
        UR[s]?.let { return it }
        for ((re, tpl) in PATTERNS) {
            val m = re.matchEntire(s) ?: continue
            var out = tpl
            m.groupValues.drop(1).forEachIndexed { i, g -> out = out.replace("{$i}", tr(g)) }
            return out
        }
        return s
    }
}

/** Material 3 Text with the app's translation applied: a drop-in replacement (same parameters). */
@Composable
fun Text(
    text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null, fontWeight: FontWeight? = null, fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified, textDecoration: TextDecoration? = null, textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified, overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE, minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    I18n.tr(text), modifier, color, fontSize, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration, textAlign,
    lineHeight, overflow, softWrap, maxLines, minLines, onTextLayout, style,
)

private val PATTERNS: List<Pair<Regex, String>> = listOf(
    Regex("Signed by (.+) · (.+)") to "{0} کے دستخط · {1}",
    Regex("Signed by (.+)") to "{0} کے دستخط",
    Regex("Copied (\\d+) characters") to "{0} حروف کاپی ہو گئے",
    Regex("Reading page (\\d+)…") to "صفحہ {0} پڑھا جا رہا ہے…",
    Regex("Paused · page (\\d+)") to "رکا ہوا · صفحہ {0}",
    Regex("Add bookmark for page (\\d+)") to "صفحہ {0} کا بک مارک بنائیں",
    Regex("Apply (\\d+)") to "{0} لاگو کریں",
    Regex("Apply (\\d+) redaction\\(s\\)\\?") to "{0} ریڈیکشن لاگو کریں؟",
    Regex("Marked (\\d+) match\\(es\\) on (\\d+) page\\(s\\)\\. Check them, then tap Apply\\.") to "{1} صفحات پر {0} نشان لگ گئے۔ دیکھ لیں، پھر لاگو کریں دبائیں۔",
    Regex("Whole paragraph \\((\\d+) lines\\)") to "پورا پیراگراف ({0} سطریں)",
    Regex("Signatures \\((\\d+)\\)") to "دستخط ({0})",
    Regex("Comments & changes \\((\\d+)\\)") to "تبصرے اور تبدیلیاں ({0})",
    Regex("Font: (.+)") to "فونٹ: {0}",
    Regex("Page (\\d+)") to "صفحہ {0}",
    Regex("(.+) · page (\\d+)") to "{0} · صفحہ {1}",
    Regex("Temporary files: (.+)") to "عارضی فائلیں: {0}",
    Regex("Font added: (.+)\\. New text uses it\\.") to "فونٹ شامل ہو گیا: {0}۔ نئی تحریر اسی میں ہو گی۔",
    Regex("Inserted (.+)") to "شامل ہو گئے: {0}",
    Regex("Certificate of (.+) ready") to "{0} کا سرٹیفکیٹ تیار ہے",
    Regex("Detected (.+) GB memory, (\\d+) cores: (.+)") to "{0} GB میموری، {1} کور: {2}",
)

private val UR: Map<String, String> = mapOf(
    // ---- general
    "Save" to "محفوظ کریں", "Save a copy…" to "کاپی محفوظ کریں…", "Save to…" to "یہاں محفوظ کریں…", "Share" to "شیئر کریں",
    "Print" to "پرنٹ کریں", "Cancel" to "منسوخ", "Close" to "بند کریں", "Done" to "ہو گیا", "OK" to "ٹھیک ہے", "Open" to "کھولیں",
    "Delete" to "حذف کریں", "Remove" to "ہٹائیں", "Apply" to "لاگو کریں", "Add" to "شامل کریں", "Edit" to "ترمیم", "Update" to "اپ ڈیٹ",
    "Replace" to "بدلیں", "Continue" to "جاری رکھیں", "Discard" to "ضائع کریں", "Restore" to "بحال کریں", "Reset" to "دوبارہ ترتیب",
    "Undo" to "واپس", "Redo" to "دوبارہ", "Next" to "اگلا", "Previous" to "پچھلا", "More" to "مزید", "Show" to "دکھائیں", "Hide" to "چھپائیں",
    "Choose" to "منتخب کریں", "Choose…" to "منتخب کریں…", "Change" to "تبدیل کریں", "Clear" to "صاف کریں", "Go" to "جائیں", "View" to "دیکھیں",
    "Export" to "ایکسپورٹ", "Loading…" to "لوڈ ہو رہا ہے…", "Reading…" to "پڑھا جا رہا ہے…", "Preparing…" to "تیاری ہو رہی ہے…",
    "Saving…" to "محفوظ ہو رہا ہے…", "Applying changes…" to "تبدیلیاں لاگو ہو رہی ہیں…", "Unsaved changes" to "غیر محفوظ تبدیلیاں",
    "Restore unsaved work?" to "غیر محفوظ کام بحال کریں؟",
    "You have unsaved changes to this document from an earlier session." to "اس دستاویز میں پچھلی بار کی غیر محفوظ تبدیلیاں موجود ہیں۔",
    "Small" to "چھوٹا", "Normal" to "عام", "Large" to "بڑا", "Low" to "کم", "High" to "زیادہ", "Size" to "سائز", "Position" to "جگہ",
    "Left" to "بائیں", "Right" to "دائیں", "Centre" to "درمیان", "Top" to "اوپر", "Bottom" to "نیچے", "Top right" to "اوپر دائیں",
    "Bottom right" to "نیچے دائیں", "Quality" to "معیار", "Format" to "فارمیٹ", "Title" to "عنوان", "Author" to "مصنف", "Subject" to "موضوع",
    "Password" to "پاس ورڈ", "Repeat password" to "پاس ورڈ دوبارہ", "Password required" to "پاس ورڈ درکار ہے",
    "Wrong password, try again" to "غلط پاس ورڈ، دوبارہ کوشش کریں", "Unlock" to "کھولیں",

    // ---- home & tabs
    "PDF Editor" to "پی ڈی ایف ایڈیٹر", "Private, offline and light on your phone" to "نجی، آف لائن، اور فون پر ہلکا",
    "Open a PDF" to "PDF کھولیں", "Recent" to "حالیہ", "Files you open appear here." to "جو فائلیں آپ کھولیں گے وہ یہاں نظر آئیں گی۔",
    "Remove from recent" to "حالیہ سے ہٹائیں", "Home" to "ہوم", "Tools" to "ٹولز", "Settings" to "ترتیبات",
    "Working file" to "زیرِ کار فائل", "None selected" to "کوئی منتخب نہیں", "Choose a PDF first" to "پہلے PDF منتخب کریں",
    "This PDF could not be displayed." to "یہ PDF نہیں دکھائی جا سکی۔",

    // ---- viewer menu & tools
    "Read" to "پڑھیں", "Edit text" to "تحریر بدلیں", "Sign" to "دستخط", "Picture" to "تصویر", "Stamp" to "مہر", "Highlight" to "نمایاں کریں",
    "Underline" to "خط کشیدہ", "Strike" to "کاٹیں", "Pen" to "قلم", "Marker" to "مارکر", "Text" to "تحریر", "Tick" to "نشان", "Note" to "نوٹ",
    "Redact" to "چھپائیں (ریڈیکٹ)", "Box" to "خانہ", "Oval" to "بیضوی", "Line" to "لکیر", "Arrow" to "تیر", "Cloud" to "بادل",
    "Area" to "حصہ", "Eraser" to "ربڑ", "Move" to "ہلائیں", "Find" to "تلاش", "Find in document" to "دستاویز میں تلاش",
    "Close search" to "تلاش بند کریں", "Night mode" to "رات کا موڈ", "Day mode" to "دن کا موڈ", "Go to page…" to "صفحے پر جائیں…",
    "Bookmarks" to "بک مارکس", "Fill form" to "فارم بھریں", "Recognize text (OCR)" to "تحریر پہچانیں (OCR)", "More tools…" to "مزید ٹولز…",
    "Read aloud" to "بلند آواز سے پڑھیں", "Comments & changes" to "تبصرے اور تبدیلیاں", "Digital signature…" to "ڈیجیٹل دستخط…",
    "Digital signature" to "ڈیجیٹل دستخط", "Find & redact…" to "تلاش کر کے چھپائیں…", "Fit text width" to "تحریر کی چوڑائی پر فٹ کریں",
    "Fit page" to "پورا صفحہ", "Organize pages" to "صفحات ترتیب دیں", "Pause" to "روکیں", "Resume" to "جاری رکھیں", "Stop reading" to "پڑھنا بند کریں",
    "Finished reading" to "پڑھنا مکمل ہو گیا",
    "Read-only: the author of this PDF does not allow changes." to "صرف پڑھنے کے لیے: اس PDF کے مصنف نے تبدیلی کی اجازت نہیں دی۔",
    "Tap outlined text to change it · colour = font" to "تحریر بدلنے کے لیے نشان زدہ حصے پر ٹیپ کریں · رنگ = فونٹ",
    "Drag to move it; pinch with two fingers to resize" to "ہلانے کے لیے کھینچیں؛ سائز بدلنے کے لیے دو انگلیوں سے چٹکی لیں",
    "Drag to move · pinch to resize" to "کھینچ کر ہلائیں · چٹکی سے سائز بدلیں",
    "Drag over the words" to "الفاظ پر انگلی پھیریں", "Drag a box: works on scans and pictures too" to "خانہ بنائیں: اسکین اور تصویروں پر بھی چلتا ہے",
    "Tap where the note goes · tap a note to read or change it" to "جہاں نوٹ لگانا ہو وہاں ٹیپ کریں · نوٹ پڑھنے یا بدلنے کے لیے اس پر ٹیپ کریں",
    "Tap on one of the outlined texts" to "نشان زدہ تحریر میں سے کسی پر ٹیپ کریں",
    "No app can open this link" to "کوئی ایپ یہ لنک نہیں کھول سکتی", "Open link?" to "لنک کھولیں؟",
    "This PDF has a form. Tap a blue box, or use Fill form." to "اس PDF میں فارم ہے۔ نیلے خانے پر ٹیپ کریں یا فارم بھریں استعمال کریں۔",
    "This field is read-only" to "یہ خانہ صرف پڑھنے کے لیے ہے",
    "This form type (XFA) can be viewed but not filled in here." to "اس قسم کا فارم (XFA) دیکھا جا سکتا ہے مگر یہاں بھرا نہیں جا سکتا۔",
    "This page has no selectable text (scanned?). Use the Marker instead." to "اس صفحے پر منتخب ہونے والی تحریر نہیں (اسکین؟)۔ مارکر استعمال کریں۔",
    "The text already fills the width" to "تحریر پہلے ہی پوری چوڑائی میں ہے", "No text to fit on this page" to "اس صفحے پر فٹ کرنے کے لیے تحریر نہیں",
    "No matches found" to "کچھ نہیں ملا",

    // ---- edit text, fonts
    "Edit paragraph" to "پیراگراف بدلیں", "Editing text…" to "تحریر بدلی جا رہی ہے…", "Font" to "فونٹ", "Original" to "اصل",
    "Sans" to "سادہ", "Serif" to "سیرف", "Mono" to "یکساں چوڑائی", "Bold" to "موٹا", "Italic" to "ترچھا", "Scanned text" to "اسکین شدہ تحریر",
    "Scanned text (OCR)" to "اسکین شدہ تحریر (OCR)", "Add text" to "تحریر شامل کریں", "Adding font…" to "فونٹ شامل ہو رہا ہے…",
    "Copy style from text on the page" to "صفحے کی تحریر سے انداز کاپی کریں",
    "Tap any text on the page to copy its font, size and colour" to "صفحے کی کسی بھی تحریر پر ٹیپ کریں تاکہ اس کا فونٹ، سائز اور رنگ کاپی ہو",
    "No text there. Tap on a word of the page to copy its style" to "وہاں تحریر نہیں۔ انداز کاپی کرنے کے لیے کسی لفظ پر ٹیپ کریں",
    "This font can't be used (only TrueType .ttf / TrueType-based .otf fonts work)" to "یہ فونٹ استعمال نہیں ہو سکتا (صرف TrueType .ttf فونٹ چلتے ہیں)",
    "Text (Latin characters)" to "تحریر (لاطینی حروف)",

    // ---- selection
    "Copy text" to "تحریر کاپی کریں", "Select all" to "سب منتخب کریں", "Clear selection" to "انتخاب ختم کریں",
    "The author of this PDF does not allow copying its text." to "اس PDF کے مصنف نے تحریر کاپی کرنے کی اجازت نہیں دی۔",

    // ---- redaction
    "Words" to "الفاظ", "Find…" to "تلاش…", "Find & mark for redaction" to "تلاش کر کے چھپانے کا نشان لگائیں",
    "Every match in the document gets a mark. Check them, then tap Apply." to "دستاویز میں ہر ملنے والی جگہ پر نشان لگے گا۔ دیکھ لیں، پھر لاگو کریں دبائیں۔",
    "Words or phrases (comma between them)" to "الفاظ یا جملے (درمیان میں کوما)", "Also find" to "یہ بھی تلاش کریں",
    "Mark all matches" to "سب پر نشان لگائیں", "Redacting…" to "چھپایا جا رہا ہے…",
    "Redaction marks not applied" to "چھپانے کے نشان لاگو نہیں ہوئے", "Apply, then continue" to "لاگو کر کے جاری رکھیں",
    "Drop marks" to "نشان ہٹا دیں", "Redaction mark (not applied)" to "چھپانے کا نشان (لاگو نہیں)",
    "Drag a box over anything to remove" to "جو ہٹانا ہو اس پر خانہ بنائیں",
    "Drag over the words to remove · tap Move to adjust a mark" to "ہٹانے کے لیے الفاظ پر انگلی پھیریں · نشان درست کرنے کے لیے ہلائیں دبائیں",
    "Marked for redaction. Open the Redact tool to apply." to "چھپانے کا نشان لگ گیا۔ لاگو کرنے کے لیے ریڈیکٹ ٹول کھولیں۔",
    "Redacted: the marked areas are now blacked out" to "چھپا دیا گیا: نشان زدہ حصے اب سیاہ ہیں",
    "E-mail addresses" to "ای میل پتے", "Phone numbers" to "فون نمبر", "Card numbers" to "کارڈ نمبر", "Dates" to "تاریخیں",
    "IBAN numbers" to "IBAN نمبر", "Social security numbers" to "سوشل سکیورٹی نمبر",

    // ---- notes, comments, stamps, signing
    "New note" to "نیا نوٹ", "Note (empty = delete)" to "نوٹ (خالی = حذف)", "Drawing" to "ڈرائنگ", "Strikethrough" to "کٹا ہوا",
    "Signature" to "دستخط", "Signature (photo)" to "دستخط (تصویر)", "Edited text" to "بدلی ہوئی تحریر", "Area highlight" to "نمایاں حصہ",
    "Cross" to "کراس", "Dot" to "نقطہ", "Your own text" to "اپنی تحریر", "Draw your signature" to "اپنے دستخط کریں",
    "Sign here with your finger" to "یہاں انگلی سے دستخط کریں", "Sign here" to "یہاں دستخط کریں", "Undo stroke" to "آخری لکیر واپس",
    "Use a photo of my signature" to "اپنے دستخط کی تصویر استعمال کریں", "Use my saved signature photo" to "محفوظ دستخط کی تصویر استعمال کریں",
    "Use saved" to "محفوظ شدہ استعمال کریں", "Reading signature…" to "دستخط پڑھے جا رہے ہیں…", "Adding picture…" to "تصویر شامل ہو رہی ہے…",
    "Set up your signing identity" to "اپنی دستخطی شناخت بنائیں", "Your full name" to "آپ کا پورا نام",
    "Create my signing key" to "میری دستخطی کلید بنائیں", "Creating your signing key…" to "آپ کی دستخطی کلید بن رہی ہے…",
    "Import a certificate (.p12 / .pfx)" to "سرٹیفکیٹ امپورٹ کریں (.p12 / .pfx)", "Certificate password" to "سرٹیفکیٹ کا پاس ورڈ",
    "Use one issued to you by a certificate authority or your organisation." to "وہ استعمال کریں جو کسی سرٹیفکیٹ ادارے یا آپ کی تنظیم نے جاری کیا ہو۔",
    "Signing as" to "دستخط کنندہ", "Key made on this phone" to "اس فون پر بنی کلید", "Use a different identity" to "دوسری شناخت استعمال کریں",
    "Reason (optional)" to "وجہ (اختیاری)", "Place (optional)" to "جگہ (اختیاری)", "I approve this document" to "میں اس دستاویز کی منظوری دیتا/دیتی ہوں",
    "Sign document" to "دستاویز پر دستخط کریں", "Signing…" to "دستخط ہو رہے ہیں…", "Valid" to "درست", "Valid, but changed after signing" to "درست، مگر دستخط کے بعد بدلی گئی",
    "Invalid: altered" to "غلط: تبدیل شدہ", "Can't be checked" to "جانچ نہیں ہو سکتی", "This will break the signature" to "اس سے دستخط ٹوٹ جائیں گے",
    "Identity: self-issued (not vouched for by an authority)" to "شناخت: خود جاری کردہ (کسی ادارے کی تصدیق نہیں)",

    // ---- organize & bookmarks
    "Rotate" to "گھمائیں", "Earlier" to "پہلے", "Later" to "بعد میں", "Duplicate" to "نقل بنائیں", "Blank" to "خالی صفحہ", "Extract" to "الگ کریں",
    "Crop" to "تراشیں", "Crop pages" to "صفحات تراشیں", "Remove crop" to "تراش ہٹائیں", "Apply to all pages" to "تمام صفحات پر لاگو کریں",
    "Insert pages from another PDF" to "دوسری PDF سے صفحات شامل کریں", "Inserting pages…" to "صفحات شامل ہو رہے ہیں…",
    "Bookmark" to "بک مارک", "Search bookmarks" to "بک مارکس میں تلاش", "Bookmarks saved" to "بک مارکس محفوظ ہو گئے",
    "Bookmarks removed" to "بک مارکس ہٹا دیے گئے", "Saving bookmarks…" to "بک مارکس محفوظ ہو رہے ہیں…",
    "This document has no bookmarks." to "اس دستاویز میں بک مارکس نہیں ہیں۔", "No page" to "کوئی صفحہ نہیں",
    "No bookmarks. Saving removes the outline." to "کوئی بک مارک نہیں۔ محفوظ کرنے سے فہرست ہٹ جائے گی۔",
    "Move up" to "اوپر", "Move down" to "نیچے", "Indent" to "اندر", "Less indent" to "باہر", "Page" to "صفحہ",

    // ---- tools
    "Scan to PDF" to "اسکین سے PDF", "Auto-crop, filters, OCR" to "خودکار تراش، فلٹر، OCR", "Images to PDF" to "تصاویر سے PDF",
    "From your gallery" to "گیلری سے", "Merge PDFs" to "PDF جوڑیں", "Combine files" to "فائلیں ملائیں", "Split" to "تقسیم کریں",
    "Every N pages or ranges" to "ہر N صفحات یا حدود", "Extract pages" to "صفحات الگ کریں", "Keep only some pages" to "صرف کچھ صفحات رکھیں",
    "Compress" to "چھوٹا کریں", "Make the file smaller" to "فائل کا سائز کم کریں", "Protect" to "محفوظ کریں", "Password & permissions" to "پاس ورڈ اور اجازتیں",
    "Watermark" to "واٹر مارک", "Text across pages" to "صفحات پر تحریر", "Page numbers" to "صفحہ نمبر", "Add 1 / N" to "1 / N شامل کریں",
    "Header & footer" to "ہیڈر اور فوٹر", "Text on every page" to "ہر صفحے پر تحریر", "PDF to images" to "PDF سے تصاویر", "PNG or JPG" to "PNG یا JPG",
    "Extract text" to "تحریر نکالیں", "Extract images" to "تصاویر نکالیں", "Every picture as a file" to "ہر تصویر الگ فائل میں",
    "Attachments" to "منسلکات", "Files inside the PDF" to "PDF کے اندر فائلیں", "Compare" to "موازنہ", "What changed between two PDFs" to "دو PDF میں کیا بدلا",
    "Remove hidden info" to "پوشیدہ معلومات ہٹائیں", "Metadata, scripts, hidden text" to "میٹا ڈیٹا، اسکرپٹ، پوشیدہ تحریر",
    "Remove hidden information" to "پوشیدہ معلومات ہٹائیں", "Properties" to "خصوصیات", "Title, author, size" to "عنوان، مصنف، سائز",
    "Scan" to "اسکین", "Camera" to "کیمرا", "Gallery" to "گیلری", "Merge" to "جوڑیں", "Choose PDFs" to "PDF منتخب کریں",
    "Files are merged in this order." to "فائلیں اسی ترتیب سے جوڑی جائیں گی۔", "Merging…" to "جوڑی جا رہی ہیں…", "Splitting…" to "تقسیم ہو رہی ہے…",
    "Compressing…" to "چھوٹا کیا جا رہا ہے…", "Encrypting…" to "پاس ورڈ لگ رہا ہے…", "Adding watermark…" to "واٹر مارک لگ رہا ہے…",
    "Numbering pages…" to "صفحات پر نمبر لگ رہے ہیں…", "Exporting pages…" to "صفحات ایکسپورٹ ہو رہے ہیں…", "Extracting text…" to "تحریر نکالی جا رہی ہے…",
    "Extracting…" to "الگ کیا جا رہا ہے…", "Finding pictures…" to "تصاویر تلاش ہو رہی ہیں…", "Comparing…" to "موازنہ ہو رہا ہے…",
    "Creating PDF…" to "PDF بن رہی ہے…", "Preparing scan…" to "اسکین تیار ہو رہا ہے…", "Recognizing text…" to "تحریر پہچانی جا رہی ہے…",
    "Attaching…" to "منسلک ہو رہی ہے…", "Saving attachment…" to "منسلکہ محفوظ ہو رہا ہے…", "Adding header & footer…" to "ہیڈر اور فوٹر لگ رہے ہیں…",
    "Removing hidden information…" to "پوشیدہ معلومات ہٹائی جا رہی ہیں…", "Checking the document…" to "دستاویز جانچی جا رہی ہے…",
    "PDF created" to "PDF بن گئی", "Scan ready" to "اسکین تیار ہے", "Compressed" to "چھوٹی ہو گئی", "Password set" to "پاس ورڈ لگ گیا",
    "Watermark added" to "واٹر مارک لگ گیا", "Page numbers added" to "صفحہ نمبر لگ گئے", "Text extracted" to "تحریر نکل آئی",
    "Pages extracted" to "صفحات الگ ہو گئے", "Header & footer added" to "ہیڈر اور فوٹر لگ گئے", "Hidden information removed" to "پوشیدہ معلومات ہٹا دی گئیں",
    "Comparison ready" to "موازنہ تیار ہے", "File attached" to "فائل منسلک ہو گئی", "Attachment ready" to "منسلکہ تیار ہے",
    "Properties updated" to "خصوصیات اپ ڈیٹ ہو گئیں", "Save properties" to "خصوصیات محفوظ کریں", "Attach a file" to "فائل منسلک کریں",
    "This PDF has no attached files." to "اس PDF میں کوئی منسلک فائل نہیں۔", "This PDF has no pictures" to "اس PDF میں کوئی تصویر نہیں",
    "This PDF is already as small as it can get." to "یہ PDF پہلے ہی جتنی چھوٹی ہو سکتی ہے اتنی ہے۔",
    "No differences: the text of both files is the same" to "کوئی فرق نہیں: دونوں فائلوں کی تحریر ایک جیسی ہے",
    "Choose the other PDF" to "دوسری PDF منتخب کریں",
    "You get a report listing what was removed (red) and added (green) on each page." to "آپ کو رپورٹ ملے گی کہ ہر صفحے پر کیا ہٹا (سرخ) اور کیا شامل ہوا (سبز)۔",
    "A cleaned copy is made; your file is not changed." to "صاف شدہ کاپی بنے گی؛ آپ کی فائل نہیں بدلے گی۔",
    "Header (top of each page)" to "ہیڈر (ہر صفحے کے اوپر)", "Footer (bottom of each page)" to "فوٹر (ہر صفحے کے نیچے)",
    "Use {page}, {pages}, {date} and {file}" to "{page}، {pages}، {date} اور {file} استعمال کریں", "Start at" to "شروع",
    "Page size" to "صفحے کا سائز", "Fit image" to "تصویر کے مطابق", "JPG (small)" to "JPG (چھوٹی)", "PNG (sharp)" to "PNG (واضح)",
    "Every N pages" to "ہر N صفحات", "By ranges" to "حدود کے مطابق", "Pages per file" to "فی فائل صفحات", "Ranges (one file each)" to "حدود (ہر ایک کی الگ فائل)",
    "Pages to keep" to "رکھنے والے صفحات", "Protect with password" to "پاس ورڈ سے محفوظ کریں", "Password (min. 4 characters)" to "پاس ورڈ (کم از کم 4 حروف)",
    "Allow people who open it to:" to "کھولنے والوں کو اجازت دیں:", "Rotated pages are skipped" to "گھمائے گئے صفحات چھوڑ دیے جاتے ہیں",
    "A PDF needs at least one page." to "PDF میں کم از کم ایک صفحہ ہونا چاہیے۔", "Add more" to "مزید شامل کریں",
    "Add photos from the camera or your gallery." to "کیمرا یا گیلری سے تصاویر شامل کریں۔", "Document scanner unavailable" to "دستاویز اسکینر دستیاب نہیں",
    "Document scanner unavailable here, using the camera instead" to "یہاں دستاویز اسکینر دستیاب نہیں، اس کے بجائے کیمرا استعمال ہو رہا ہے",
    "Flatten when saving (no longer editable)" to "محفوظ کرتے وقت ہموار کریں (پھر بدلی نہیں جا سکے گی)",
    "Highlight fields on the page" to "صفحے پر خانے نمایاں کریں", "Calculated from other fields" to "دوسرے خانوں سے حساب شدہ",
    "Calculated: changes when the fields it adds up change" to "حساب شدہ: جن خانوں کا مجموعہ ہے ان کے بدلنے پر بدلتا ہے",
    "Checked" to "نشان لگا", "Not checked" to "نشان نہیں",

    // ---- settings
    "This phone" to "یہ فون", "Budget-phone mode" to "سستے فون کا موڈ",
    "Smaller page images and caches, as on a 1–2 GB phone" to "چھوٹی تصاویر اور کیش، جیسے 1–2 GB والے فون پر",
    "Signing" to "دستخط", "Saved signature photo" to "محفوظ دستخط کی تصویر", "Saved hand-drawn signature" to "محفوظ ہاتھ کے دستخط",
    "Uploaded fonts" to "اپ لوڈ کیے گئے فونٹ", "Storage" to "اسٹوریج", "Temporary files cleared" to "عارضی فائلیں صاف ہو گئیں",
    "Copies kept for undo, exports, page images. Your documents are not affected." to "واپسی کے لیے رکھی کاپیاں، ایکسپورٹس، صفحات کی تصاویر۔ آپ کی دستاویزات متاثر نہیں ہوں گی۔",
    "About" to "تعارف", "Open-source software" to "اوپن سورس سافٹ ویئر", "Licence" to "لائسنس", "Language" to "زبان",
    "Follow the phone" to "فون کے مطابق", "English" to "English", "اردو" to "اردو",
    "Works offline. No account, no tracking: your files never leave the phone unless you share them." to "آف لائن چلتا ہے۔ کوئی اکاؤنٹ نہیں، کوئی نگرانی نہیں: آپ کی فائلیں فون سے باہر نہیں جاتیں جب تک آپ خود شیئر نہ کریں۔",
    "a fast phone: full quality, up to 6× zoom" to "تیز فون: مکمل معیار، 6× تک زوم", "a mid-range phone: balanced quality" to "درمیانہ فون: متوازن معیار",
    "a budget phone: light on memory" to "سستا فون: کم میموری",

    // ---- labels with a leading space (next to an icon), messages
    " Copy" to " کاپی", " Share" to " شیئر", " All" to " سب", " Highlight" to " نمایاں", " Redact" to " چھپائیں", " Font" to " فونٹ",
    "  Match case" to "  حروف کی شکل ملائیں", "Import" to "امپورٹ", "Importing certificate…" to "سرٹیفکیٹ امپورٹ ہو رہا ہے…",
    "Opening…" to "کھل رہی ہے…", "Unlocking…" to "کھولی جا رہی ہے…", "Saved" to "محفوظ ہو گیا", "Look" to "انداز",
    "Edit and annotate" to "ترمیم اور نشانات", "Save as .txt" to ".txt کے طور پر محفوظ کریں", "Remove last page" to "آخری صفحہ ہٹائیں",
    "e.g. 1-3, 5, 8-" to "مثلاً 1-3، 5، 8-",
    "No digital-signature identity. Create one from ⋮ → Digital signature… in a document." to "کوئی ڈیجیٹل دستخطی شناخت نہیں۔ کسی دستاویز میں ⋮ → ڈیجیٹل دستخط… سے بنائیں۔",
    "A file is password-protected. Open it first, then use its unlocked copy." to "ایک فائل پاس ورڈ سے محفوظ ہے۔ پہلے اسے کھولیں، پھر اس کی کھلی کاپی استعمال کریں۔",
    "All pages already have text: nothing to recognize" to "تمام صفحات پر پہلے سے تحریر ہے: پہچاننے کو کچھ نہیں",
    "Applies to all selected pages. Nothing is deleted: cropping only hides the edges." to "تمام منتخب صفحات پر لاگو ہو گا۔ کچھ حذف نہیں ہوتا: تراشنا صرف کنارے چھپاتا ہے۔",
    "Images are re-encoded smaller; text and vectors stay sharp." to "تصاویر چھوٹی کی جاتی ہیں؛ تحریر اور لکیریں واضح رہتی ہیں۔",
    "Not enough memory for this file. Try a smaller PDF or close other apps." to "اس فائل کے لیے میموری کافی نہیں۔ چھوٹی PDF آزمائیں یا دوسری ایپس بند کریں۔",
    "Nothing yet. Notes, highlights, drawings and other changes you make are listed here." to "ابھی کچھ نہیں۔ آپ کے نوٹ، نمایاں کیے حصے، ڈرائنگ اور دوسری تبدیلیاں یہاں نظر آئیں گی۔",
    "Permission to read this file was lost. Open it again from the file picker." to "اس فائل کو پڑھنے کی اجازت ختم ہو گئی۔ فائل منتخب کرنے والے سے دوبارہ کھولیں۔",
    "Proves who signed and that nothing has changed since. Sign last: any change after signing breaks it." to "ثابت کرتا ہے کہ کس نے دستخط کیے اور اس کے بعد کچھ نہیں بدلا۔ دستخط آخر میں کریں: بعد کی کوئی بھی تبدیلی اسے توڑ دے گی۔",
    "The author of this PDF does not allow that." to "اس PDF کے مصنف نے اس کی اجازت نہیں دی۔",
    "The file is no longer available." to "فائل اب دستیاب نہیں۔",
    "There is no way to recover a forgotten password. Keep an unprotected copy somewhere safe." to "بھولا ہوا پاس ورڈ واپس حاصل کرنے کا کوئی طریقہ نہیں۔ بغیر پاس ورڈ کی کاپی کہیں محفوظ رکھیں۔",
    "This file was damaged and has been repaired." to "یہ فائل خراب تھی اور اسے ٹھیک کر دیا گیا ہے۔",
    "This page has no editable text. If it is a scan, use ⋮ → Recognize text (OCR) first." to "اس صفحے پر بدلنے والی تحریر نہیں۔ اگر یہ اسکین ہے تو پہلے ⋮ → تحریر پہچانیں (OCR) استعمال کریں۔",
    "Everything under the marks (text, images, drawings, links and comments) is removed from the file, not just covered, and replaced by black boxes. You can undo until you close this file; once saved, it's permanent." to
        "نشانات کے نیچے ہر چیز (تحریر، تصاویر، ڈرائنگ، لنکس اور تبصرے) فائل سے ہٹا دی جاتی ہے، صرف ڈھانپی نہیں جاتی، اور اس کی جگہ سیاہ خانے آ جاتے ہیں۔ فائل بند کرنے تک واپس لے سکتے ہیں؛ محفوظ کرنے کے بعد یہ مستقل ہے۔",
    "The key is made and kept in this phone's secure storage; it never leaves it. Readers will see your name; no authority vouches for it, so their app may call the identity \"unknown\"." to
        "کلید اسی فون کے محفوظ حصے میں بنتی اور رہتی ہے؛ یہ کبھی باہر نہیں جاتی۔ پڑھنے والے آپ کا نام دیکھیں گے؛ کوئی ادارہ اس کی تصدیق نہیں کرتا، اس لیے ان کی ایپ شناخت کو \"نامعلوم\" کہہ سکتی ہے۔",
    "This document's fonts need the compatible renderer on this phone: pages may appear a little slower" to "اس فون پر اس دستاویز کے فونٹس کے لیے متبادل طریقہ استعمال ہو رہا ہے: صفحات قدرے دیر سے نظر آ سکتے ہیں",
    "Find text to redact" to "چھپانے کے لیے تحریر تلاش کریں",
)
