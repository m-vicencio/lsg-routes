package cl.usach.diinf.lsg.routes.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/** Modo de tema seleccionable por el usuario. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val LightColors = lightColorScheme(
    primary = md_primary_light,
    onPrimary = md_onPrimary_light,
    primaryContainer = md_primaryContainer_light,
    onPrimaryContainer = md_onPrimaryContainer_light,
    secondary = md_secondary_light,
    onSecondary = md_onSecondary_light,
    secondaryContainer = md_secondaryContainer_light,
    onSecondaryContainer = md_onSecondaryContainer_light,
    tertiary = md_tertiary_light,
    onTertiary = md_onTertiary_light,
    tertiaryContainer = md_tertiaryContainer_light,
    onTertiaryContainer = md_onTertiaryContainer_light,
    error = md_error_light,
    onError = md_onError_light,
    errorContainer = md_errorContainer_light,
    background = md_background_light,
    onBackground = md_onBackground_light,
    surface = md_surface_light,
    onSurface = md_onSurface_light,
    surfaceVariant = md_surfaceVariant_light,
    onSurfaceVariant = md_onSurfaceVariant_light,
    outline = md_outline_light,
)

private val DarkColors = darkColorScheme(
    primary = md_primary_dark,
    onPrimary = md_onPrimary_dark,
    primaryContainer = md_primaryContainer_dark,
    onPrimaryContainer = md_onPrimaryContainer_dark,
    secondary = md_secondary_dark,
    onSecondary = md_onSecondary_dark,
    secondaryContainer = md_secondaryContainer_dark,
    onSecondaryContainer = md_onSecondaryContainer_dark,
    tertiary = md_tertiary_dark,
    onTertiary = md_onTertiary_dark,
    tertiaryContainer = md_tertiaryContainer_dark,
    onTertiaryContainer = md_onTertiaryContainer_dark,
    error = md_error_dark,
    onError = md_onError_dark,
    errorContainer = md_errorContainer_dark,
    background = md_background_dark,
    onBackground = md_onBackground_dark,
    surface = md_surface_dark,
    onSurface = md_onSurface_dark,
    surfaceVariant = md_surfaceVariant_dark,
    onSurfaceVariant = md_onSurfaceVariant_dark,
    outline = md_outline_dark,
)

/**
 * Tema Material3 de LSG Routes. Colores de marca fijos (sin Material You)
 * para una identidad verde consistente en todos los dispositivos.
 */
@Composable
fun LsgRoutesTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
