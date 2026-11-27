# PdfBox-Android: per its README nothing needs keeping; silence optional dependencies.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.xml.stream.**
-dontwarn org.apache.commons.logging.**
# PdfBox reads these resources by class-relative path; keep the classes that own them.
-keep class com.tom_roush.pdfbox.android.PDFBoxResourceLoader { *; }
-keepclassmembers class com.tom_roush.fontbox.** { <init>(...); }

# SafePageDrawer (com.tom_roush.pdfbox.rendering.SafeRendering.kt) reaches these by reflection
-keepclassmembers class com.tom_roush.pdfbox.rendering.PageDrawer {
    private java.util.Map fontGlyph2D;
    private com.tom_roush.pdfbox.rendering.Glyph2D createGlyph2D(com.tom_roush.pdfbox.pdmodel.font.PDFont);
}
