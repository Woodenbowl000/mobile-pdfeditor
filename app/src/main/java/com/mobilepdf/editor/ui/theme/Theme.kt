package com.mobilepdf.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFB70228), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8), onPrimaryContainer = Color(0xFF410006),
    secondaryContainer = Color(0xFFF6DDDB), onSecondaryContainer = Color(0xFF2C1514),
    background = Color(0xFFFFFBFF), surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF5DDDB), onSurfaceVariant = Color(0xFF534342),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFFFB3B0), onPrimary = Color(0xFF680013),
    primaryContainer = Color(0xFF93001D), onPrimaryContainer = Color(0xFFFFDAD8),
    secondaryContainer = Color(0xFF5C3F3D), onSecondaryContainer = Color(0xFFFFDAD8),
    background = Color(0xFF1A1111), surface = Color(0xFF1A1111),
    surfaceVariant = Color(0xFF534342), onSurfaceVariant = Color(0xFFD8C2BF),
)

@Composable
fun PdfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
