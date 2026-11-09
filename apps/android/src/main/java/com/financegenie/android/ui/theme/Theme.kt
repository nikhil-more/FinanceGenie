package com.financegenie.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F0D2),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6357),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFF8FAF7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF98D4B7),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFB4F0D2),
    secondary = Color(0xFFB3CCBC),
    background = Color(0xFF101512),
    surface = Color(0xFF101512),
)

@Composable
fun FinanceGenieTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
