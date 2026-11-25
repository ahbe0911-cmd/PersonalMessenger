package com.personal.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.*
import com.personal.messenger.R

private val light = lightColorScheme(primary=Color(0xFF008069), onPrimary=Color.White,
    background=Color(0xFFEFEAE2), surface=Color.White, onSurface=Color(0xFF182622),
    surfaceVariant=Color.White, onSurfaceVariant=Color(0xFF5F716D))
private val dark = darkColorScheme(primary=Color(0xFF53C8A5), onPrimary=Color(0xFF00382D),
    background=Color(0xFF0B141A), surface=Color(0xFF202C33), onSurface=Color(0xFFE8F0EF),
    surfaceVariant=Color(0xFF2A3942), onSurfaceVariant=Color(0xFFA8B8BB))
@Composable
fun MessengerTheme(darkMode: Boolean? = null, content: @Composable () -> Unit) {
    val family = FontFamily(Font(R.font.vazirmatn_regular), Font(R.font.vazirmatn_bold, FontWeight.Bold))
    val t = Typography()
    val typography = Typography(
        displayLarge=t.displayLarge.copy(fontFamily=family), displayMedium=t.displayMedium.copy(fontFamily=family),
        displaySmall=t.displaySmall.copy(fontFamily=family), headlineLarge=t.headlineLarge.copy(fontFamily=family),
        headlineMedium=t.headlineMedium.copy(fontFamily=family), headlineSmall=t.headlineSmall.copy(fontFamily=family),
        titleLarge=t.titleLarge.copy(fontFamily=family), titleMedium=t.titleMedium.copy(fontFamily=family,fontWeight=FontWeight.Bold),
        titleSmall=t.titleSmall.copy(fontFamily=family), bodyLarge=t.bodyLarge.copy(fontFamily=family),
        bodyMedium=t.bodyMedium.copy(fontFamily=family), bodySmall=t.bodySmall.copy(fontFamily=family),
        labelLarge=t.labelLarge.copy(fontFamily=family), labelMedium=t.labelMedium.copy(fontFamily=family),
        labelSmall=t.labelSmall.copy(fontFamily=family)
    )
    MaterialTheme(colorScheme=if(darkMode ?: isSystemInDarkTheme()) dark else light, typography=typography, content=content)
}
