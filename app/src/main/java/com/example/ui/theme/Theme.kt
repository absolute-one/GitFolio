package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkObsidianScheme = darkColorScheme(
    primary = ElectricAmber,
    onPrimary = Color(0xFF14161B),
    primaryContainer = ElectricAmberContainer,
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = CyberMint,
    onSecondary = Color(0xFF0A201E),
    secondaryContainer = CyberMintContainer,
    onSecondaryContainer = Color(0xFFB3F3EC),
    tertiary = LavenderPop,
    onTertiary = Color(0xFF1E1436),
    tertiaryContainer = LavenderContainer,
    onTertiaryContainer = Color(0xFFE7DEFF),
    background = ObsidianBackground,
    onBackground = IvoryTextPrimary,
    surface = ObsidianSurface,
    onSurface = IvoryTextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = IvoryTextSecondary,
    outline = ObsidianBorder,
    outlineVariant = Color(0xFF232834)
)

private val LightEditorialScheme = lightColorScheme(
    primary = Color(0xFFD94E1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2D6),
    onPrimaryContainer = Color(0xFF3E1203),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFF4F46E5),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    background = PaperBackground,
    onBackground = InkTextPrimary,
    surface = PaperSurface,
    onSurface = InkTextPrimary,
    surfaceVariant = PaperSurfaceElevated,
    onSurfaceVariant = InkTextSecondary,
    outline = PaperBorder,
    outlineVariant = Color(0xFFE3DDD1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkObsidianScheme else LightEditorialScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
